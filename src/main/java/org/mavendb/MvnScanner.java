package org.mavendb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Gatherers;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.index.reader.ChunkReader;
import org.apache.maven.index.reader.IndexReader;
import org.apache.maven.index.reader.Record;
import org.apache.maven.index.reader.RecordExpander;
import org.apache.maven.index.reader.ResourceHandler;
import org.apache.maven.index.reader.WritableResourceHandler;
import org.apache.maven.index.reader.resource.PathWritableResourceHandler;
import org.apache.maven.index.reader.resource.UriResourceHandler;
import org.bson.Document;

/**
 * Scan all artifacts in maven repository.
 *
 * @see <a href="https://github.com/apache/maven-indexer/blob/master/indexer-reader/src/test/java/org/apache/maven/index/reader/IndexReaderTest.java">IndexReaderTest</a>
 */
class MvnScanner {

    /**
     * SQL script to create schema.
     */
    private static final String DB_CREATE_SQL = "create.sql";

    /**
     * SQL script to refresh data.
     */
    private static final String DB_DATA_REFRESH_SQL = "data-refresh.sql";

    /** Logger. */
    private static final Logger LOG = Logger.getLogger(MvnScanner.class.getName());

    /**
     * Index folder, from the <code>--reposfolder</code> command line option.
     */
    private final URI indexFolder;

    /**
     * Target database type, from the <code>--dbtype</code> command line option.
     */
    private final DatabaseType dbType;

    /**
     * Maven repo Index ID.
     * The value is the property "nexus.index.id" in nexus-maven-repository-index.properties file.
     * Example: central.
     */
    private String indexId;

    /**
     * Configuration manager for loading settings.
     */
    private ConfigurationManager configMgr;

    /**
     * Database repository for storing records and documents.
     */
    private DatabaseRepository databaseRepository;

    /**
     * Limits the number of batches written to the database at the same time.
     * Created in {@link #perform(Properties)}, see {@link #stepScan(int)} for why it is needed.
     */
    private Semaphore writerPermits;

    /**
     * Record sequence, unique across all chunks: it is the primary key (seqid / _id).
     * Assigned in index order on the scanning thread, before the records are handed to the conversion threads.
     */
    private long recordSeq;

    /**
     * Track the max length of string values for each key for logging and potential future optimizations.
     * Each conversion task tracks its own batch and merges it in once, see {@link #convertBatch(List)}.
     */
    private final Map<String, Integer> maxLengthTracker = new ConcurrentSkipListMap<>();

    /**
     * One raw index record with its sequence number.
     */
    private record RawRecord(long seq, Map<String, String> data) {
    }

    /**
     * One converted batch, ready to write: documents for MongoDB, prepared rows for the SQL databases.
     *
     * @param counter Sequence number of the last record, for logging
     * @param size Number of records to write
     * @param documents MongoDB documents, <code>null</code> for SQL databases
     * @param rows SQL rows, <code>null</code> for MongoDB
     */
    private record Batch(long counter, int size, List<Document> documents, List<DatabaseRepository.SqlRow> rows) {
    }

    /**
     * Private constructor - use {@link #create(String, DatabaseType)} factory method instead.
     */
    private MvnScanner(URI indexFolder, DatabaseType dbType) {
        this.indexFolder = indexFolder;
        this.dbType = dbType;
    }

    /**
     * Factory method to safely create a MvnScanner instance.
     * Validates the index folder path before object construction to prevent
     * partially initialized objects vulnerable to finalizer attacks.
     *
     * @param folderPath The folder path to scan
     * @param dbType The database type to use
     * @return A validated MvnScanner instance
     * @throws IllegalArgumentException if the path is invalid or contains suspicious patterns
     */
    public static MvnScanner create(String folderPath, DatabaseType dbType) throws IllegalArgumentException {
        URI validatedUri = validateAndCreateURI(folderPath);
        return new MvnScanner(validatedUri, dbType);
    }

    /**
     * Validates the index folder path and converts it to a safe URI.
     * Ensures the path is not null, empty, and doesn't contain path traversal attempts.
     *
     * @param folderPath The folder path to validate
     * @return A validated URI
     * @throws IllegalArgumentException if the path is invalid or contains suspicious patterns
     */
    private static URI validateAndCreateURI(String folderPath) throws IllegalArgumentException {
        // Check for common path traversal patterns
        if (folderPath.contains("..") || folderPath.contains("~")) {
            throw new IllegalArgumentException("Index folder path contains suspicious patterns: " + folderPath);
        }

        try {
            URI uri = URI.create(folderPath);

            // Validate URI scheme for file:// URIs
            if (uri.getScheme() != null && uri.getScheme().equals("file")) {
                // Normalize and validate file path
                Path path = Path.of(uri);
                // Ensure the path exists and is accessible
                if (!Files.exists(path)) {
                    throw new IllegalArgumentException("Index folder path does not exist: " + folderPath);
                }
                if (!Files.isDirectory(path)) {
                    throw new IllegalArgumentException("Index folder path is not a directory: " + folderPath);
                }
                if (!Files.isReadable(path)) {
                    throw new IllegalArgumentException("Index folder path is not readable: " + folderPath);
                }
            } else if (uri.getScheme() != null) {
                // For remote URIs (http, https, etc.), basic validation
                if (!uri.getScheme().matches("^[a-zA-Z][a-zA-Z0-9+.-]*$")) {
                    throw new IllegalArgumentException("Invalid URI scheme: " + uri.getScheme());
                }
            }

            return uri;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid index folder path: " + folderPath, e);
        }
    }


    public void perform(Properties config) throws IOException, SQLException {
        this.configMgr = new ConfigurationManager(config);

        int writers;
        if (this.dbType == DatabaseType.SQLITE) {
            LOG.log(Level.INFO, "SQLite only allows one writer, cannot write in multiple threads");
            writers = 1;
        } else {
            writers = configMgr.getThreadPoolSize();
        }
        LOG.log(Level.INFO, "Concurrent database writers: {0}", writers);
        this.writerPermits = new Semaphore(writers);

        this.databaseRepository = switch (this.dbType) {
            case MYSQL -> new DatabaseRepository(DatabaseType.MYSQL, configMgr.getMysqlUrl(), configMgr.mysqlConnectionProps);
            case PSQL -> new DatabaseRepository(DatabaseType.PSQL, configMgr.getPsqlUrl(), configMgr.psqlConnectionProps);
            case SQLITE -> new DatabaseRepository(DatabaseType.SQLITE, configMgr.getSqliteUrl(), configMgr.sqliteConnectionProps);
            case MONGODB -> new DatabaseRepository(configMgr.getMongodbUrl());
        };
        if (DatabaseRepository.SUPPORTED_SQL_DB_TYPES.contains(this.dbType)) {
            this.databaseRepository.executeSQLScript(DB_CREATE_SQL);
        }

        try {
            long start = System.currentTimeMillis();
            try {
                // Returns only after every batch is written; a failed batch throws and stops the scan
                this.stepScan(writers);
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Scan failed, the database is incomplete, rerun the import", e);
                throw e;
            }
            LOG.log(Level.INFO, "Scan execution time={0}", System.currentTimeMillis() - start);

            // All writers are done, release their pooled connections before the post-load scripts
            this.databaseRepository.closeSQLConnections();

            // Refresh Data
            if (DatabaseRepository.SUPPORTED_SQL_DB_TYPES.contains(this.dbType)) {
                this.databaseRepository.executeSQLScript(DB_DATA_REFRESH_SQL);
            } else if (this.dbType == DatabaseType.MONGODB) {
                this.databaseRepository.createIndexesMongoDB(this.indexId);
            }
        } finally {
            this.databaseRepository.close();
        }

        LOG.log(Level.INFO, "Max length of the String fields: {0}{1}", new Object[] {System.lineSeparator(),
                this.maxLengthTracker.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(System.lineSeparator()))});
    }

    /**
     * Scan maven index files and write them to the database in batches.
     *
     * <p>Pipeline (Java 24+ stream gatherers, JEP 485):
     * <pre>
     *   chunks → raw records (seq assigned) → windowFixed(batchSize)
     *          → mapConcurrent(converters, convertBatch) → mapConcurrent(writers + 1, storeBatch)
     * </pre>
     * Reading and decoding the index is sequential and runs on this thread, which also assigns the
     * record sequence in index order. Expanding records, building documents and preparing SQL rows is
     * about two thirds of the CPU work, so it runs on {@code converters} virtual threads.
     * {@code mapConcurrent} keeps the input order, so batches still reach the writers in sequence order.
     *
     * <p>Why {@code writers + 1} tasks plus {@link #writerPermits}: {@code mapConcurrent} blocks the scanning
     * thread whenever all of its task slots are busy. With exactly {@code writers} slots, the next batch
     * could only be handed over after a write finished, leaving a writer idle; for SQLite (one writer)
     * converting and writing would not overlap at all. The extra slot holds one ready batch that waits for a
     * permit, so the writers stay busy. With one writer this also keeps the SQLite writes in sequence order.
     *
     * <p>Memory: at most {@code converters + writers + 3} batches (being filled, converting, writing, one ready).
     * A failed batch throws out of this method; the other in-flight batches are cancelled.
     *
     * @param writers Maximum number of batches written at the same time
     * @throws IOException Failed to read the index
     */
    private void stepScan(int writers) throws IOException {
        Path tempDir = Files.createTempDirectory("mvn-index");

        try (
            ResourceHandler remote = new UriResourceHandler(this.indexFolder);
            WritableResourceHandler local = new PathWritableResourceHandler(tempDir);
            IndexReader indexReader = new IndexReader(local, remote)
        ) {
            this.indexId = indexReader.getIndexId();
            LOG.log(Level.INFO,"indexRepoId=" + indexReader.getIndexId());
            LOG.log(Level.INFO,"indexLastPublished=" + indexReader.getPublishedTimestamp());
            LOG.log(Level.INFO,"isIncremental=" + indexReader.isIncremental());
            LOG.log(Level.INFO,"indexRequiredChunkNames=" + indexReader.getChunkNames());

            int batchSize = switch (this.dbType) {
                case MYSQL -> this.configMgr.getMysqlBatchSize();
                case PSQL -> this.configMgr.getPsqlBatchSize();
                case SQLITE -> this.configMgr.getSqliteBatchSize();
                case MONGODB -> this.configMgr.getMongodbBatchSize();
            };

            // Conversion is CPU bound; 2-8 threads keep up with the single decoding thread
            int converters = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 8);
            LOG.log(Level.INFO, "Concurrent batch converters: {0}", converters);

            this.recordSeq = 0;
            long stored;
            try (Stream<ChunkReader> chunks = StreamSupport.stream(indexReader.spliterator(), false)) {
                stored = chunks
                    .flatMap(this::chunkRecords)
                    .gather(Gatherers.windowFixed(batchSize))
                    .gather(Gatherers.mapConcurrent(converters, this::convertBatch))
                    .filter(batch -> batch.size() > 0)
                    .gather(Gatherers.mapConcurrent(writers + 1, this::storeBatch))
                    .mapToLong(Integer::longValue)
                    .sum();
            }
            LOG.log(Level.INFO, "Scanned {0} records, stored {1} records", new Object[]{this.recordSeq, stored});
        }
    }

    /**
     * Stream the raw records of one chunk, numbered in index order; the chunk is closed when its stream is consumed.
     */
    private Stream<RawRecord> chunkRecords(ChunkReader chunkReader) {
        LOG.log(Level.INFO,"  chunkName=" + chunkReader.getName());
        LOG.log(Level.INFO,"  chunkVersion=" + chunkReader.getVersion());
        LOG.log(Level.INFO,"  chunkPublished=" + chunkReader.getTimestamp());

        return StreamSupport.stream(chunkReader.spliterator(), false)
            .map(data -> new RawRecord(++this.recordSeq, data))
            .onClose(() -> {
                try {
                    chunkReader.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
    }

    /**
     * Convert one batch of raw records. Runs on a virtual thread started by {@code mapConcurrent}.
     *
     * @param raws Raw records, in sequence order
     * @return Batch ready to write; empty when every record was skipped
     */
    private Batch convertBatch(List<RawRecord> raws) {
        // RecordExpander has no state, one per batch keeps it obviously thread confined
        RecordExpander recordExpander = new RecordExpander();
        Map<String, Integer> maxLengths = new HashMap<>();

        List<Document> documents = new ArrayList<>(raws.size());
        for (RawRecord raw : raws) {
            Document document = toDocument(raw.seq(), recordExpander.apply(raw.data()), maxLengths);
            if (document != null) {
                documents.add(document);
            }
        }
        maxLengths.forEach((key, length) -> this.maxLengthTracker.merge(key, length, Integer::max));

        if (documents.isEmpty()) {
            return new Batch(0, 0, null, null);
        }
        // Read before preparing SQL rows: that removes fields from the documents
        long counter = documents.getLast().getLong(DatabaseRepository.JSON_FIELD_ID);
        if (this.dbType == DatabaseType.MONGODB) {
            return new Batch(counter, documents.size(), documents, null);
        }

        List<DatabaseRepository.SqlRow> rows = new ArrayList<>(documents.size());
        try {
            for (Document document : documents) {
                rows.add(this.databaseRepository.toSQLRow(document));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL row preparation failed for records counter=" + counter, e);
        }
        return new Batch(counter, rows.size(), null, rows);
    }

    /**
     * Convert one index record to a JSON document.
     *
     * @param seq Record sequence number, the primary key
     * @param record Expanded index record
     * @param maxLengths Max string length per key, for this batch
     * @return The document, or <code>null</code> when the record has no version and is skipped
     */
    private Document toDocument(long seq, Record record, Map<String, Integer> maxLengths) {
        Document jsonDoc = new Document(DatabaseRepository.JSON_FIELD_ID, seq);
        record.getExpanded().forEach((k, v) -> {
            if (k.getProto().equals(String.class)) {
                String value = strip(record.getString(k));
                jsonDoc.append(k.getName(), value);

                // Track the maximum length of string values for each key for logging and potential future optimizations
                if (value != null) {
                    maxLengths.merge(k.getName(), value.length(), Integer::max);
                }
            } else if (k.getProto().equals(String[].class)) {
                List<String> stringList = new ArrayList<>();
                for (String s : record.getStringArray(k)) {
                    stringList.add(strip(s));
                }
                jsonDoc.append(k.getName(), stringList);
            } else if (k.getProto().equals(Long.class)) {
                jsonDoc.append(k.getName(), record.getLong(k));
            } else if (k.getProto().equals(Boolean.class)) {
                jsonDoc.append(k.getName(), record.getBoolean(k));
            } else {
                LOG.log(Level.WARNING,"Unrecognized key type: " + k + "=" + v + ", name=" + k.getName() + ", type=" + v.getClass().getSimpleName());
            }
        });

        // Parse version
        String versionString = record.getString(Record.VERSION);
        if (versionString == null || versionString.isBlank()) {
            // DESCRIPTOR, ALL_GROUPS and ROOT_GROUPS are index metadata and never have a version
            Level level = record.getType() == Record.Type.ARTIFACT_ADD ? Level.WARNING : Level.FINE;
            LOG.log(level, "Record without version found, skipping: {0}", record.getType());
            return null;
        }
        VersionAnalyser analyzedVersion = new VersionAnalyser(versionString);
        jsonDoc.append(VersionAnalyser.KEY_MAJOR_VERSION, analyzedVersion.majorVersion());
        jsonDoc.append(VersionAnalyser.KEY_VERSION_SEQ, analyzedVersion.versionSeq());

        // Calculate file name
        String classifier = record.getString(Record.CLASSIFIER);
        String artifactId = record.getString(Record.ARTIFACT_ID);
        String fileExtension = record.getString(Record.FILE_EXTENSION);

        String fileName = (classifier == null || classifier.isEmpty())
                ? artifactId + "-" + versionString + "." + fileExtension
                : artifactId + "-" + versionString + "-" + classifier + "." + fileExtension;
        jsonDoc.append(DatabaseRepository.JSON_FIELD_FILE_NAME, fileName);

        return jsonDoc;
    }

    /**
     * Write one batch to the database. Runs on a virtual thread started by {@code mapConcurrent}.
     *
     * @param batch Converted batch to write
     * @return Number of records written
     */
    private Integer storeBatch(Batch batch) {
        try {
            this.writerPermits.acquire();
        } catch (InterruptedException e) {
            // Cancelled because another batch failed
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to store records counter=" + batch.counter(), e);
        }
        try {
            switch (this.dbType) {
                case MYSQL, PSQL, SQLITE -> this.databaseRepository.storeSQL(batch.rows(), batch.counter());
                case MONGODB -> this.databaseRepository.storeMongoDB(batch.documents(), batch.counter(), this.indexId);
            }
        } finally {
            this.writerPermits.release();
        }
        return batch.size();
    }


    /**
     * Strip leading/trailing whitespace and double quotes from input string.
     */
    private String strip(String input) {
        return StringUtils.strip(StringUtils.stripToNull(input), "\"");
    }
}
