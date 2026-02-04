package org.mavendb;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.maven.index.reader.ChunkReader;
import org.apache.maven.index.reader.IndexReader;
import org.apache.maven.index.reader.RecordExpander;
import org.apache.maven.index.reader.ResourceHandler;
import org.apache.maven.index.reader.WritableResourceHandler;
import org.apache.maven.index.reader.resource.PathWritableResourceHandler;
import org.apache.maven.index.reader.resource.UriResourceHandler;
import org.bson.Document;
import org.mavendb.Main.DatabaseType;

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

    /**
     * SQL script to export data.
     */
    private static final String DB_EXPORT_SQL = "export.sql";

    /**
     * Directory for MySQL DB scripts.
     */
    private static final String DIR_DB_MYSQL = "db" + File.separator + "mysql";

    /**
     * Directory for PSQL DB scripts.
     */
    private static final String DIR_DB_PSQL = "db" + File.separator + "psql";

    /* ------- Executor Configuration ------- */
    private static final int EXECUTOR_CORE_THREADS = 2;
    private static final int EXECUTOR_KEEP_ALIVE_SECONDS = 60;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_30_SECONDS = 30;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_60_SECONDS = 60;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_300_SECONDS = 300;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_600_SECONDS = 600;
    private static final int EXECUTOR_FINAL_SHUTDOWN_TIMEOUT_SECONDS = 5;

    /* ------- Queue Management ------- */
    private static final int SQL_QUEUE_MAX_SIZE = 64;
    private static final int SQL_QUEUE_RESUME_SIZE = 32;
    private static final int MONGODB_QUEUE_MAX_SIZE = 40;
    private static final int MONGODB_QUEUE_RESUME_SIZE = 10;

    /** Logger. */
    private static final Logger LOG = Logger.getLogger(MvnScanner.class.getName());

    /**
     * @see {@link Main.CommandOptions#OPTION_REPOS_FOLDER}.
     */    
    private final URI indexFolder;

    /**
     * @see {@link Main.CommandOptions#OPTION_DB_TYPE}.
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
     * Virtual thread executor for asynchronous store operations.
     * Uses Java virtual threads (Project Loom) with configurable concurrency limit.
     */
    private ThreadPoolExecutor storeExecutor;

    /**
     * JSON Documents to be saved to DB.
     */
    private List<Document> dataToBeStored = new ArrayList<>();

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

        // Load max concurrent threads configuration
        int maxConcurrentThreads = configMgr.parseThreadPoolSize();
        LOG.log(Level.INFO, "Virtual thread pool size configured: {0}", maxConcurrentThreads);

        // Create bounded virtual thread executor with configured concurrency limit
        this.storeExecutor = new ThreadPoolExecutor(
            EXECUTOR_CORE_THREADS,
            maxConcurrentThreads,
            EXECUTOR_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            Thread.ofVirtual().factory()
        );

        if (this.dbType == DatabaseType.MYSQL) {
            this.databaseRepository = new DatabaseRepository(DatabaseType.MYSQL, configMgr.getMysqlUrl(), configMgr.mysqlConnectionProps);
            this.databaseRepository.executeSQLScript(Main.getDirectoryFileName(DIR_DB_MYSQL, DB_CREATE_SQL));
        } else if (this.dbType == DatabaseType.MONGODB) {
            this.databaseRepository = new DatabaseRepository(configMgr.getMongodbUrl());
        } else if (this.dbType == DatabaseType.PSQL) {
            this.databaseRepository = new DatabaseRepository(DatabaseType.PSQL, configMgr.getPsqlUrl(), configMgr.psqlConnectionProps);
            this.databaseRepository.executeSQLScript(Main.getDirectoryFileName(DIR_DB_PSQL, DB_CREATE_SQL));
        }

        long start = System.currentTimeMillis();
        this.stepScan();

        // Shutdown virtual thread executor and wait for pending tasks with exponential backoff
        shutdownExecutorGracefully();
        LOG.log(Level.INFO, "Scan execution time={0}", System.currentTimeMillis() - start);

        // Refresh Data
        if (this.dbType == DatabaseType.MYSQL) {
            this.databaseRepository.executeSQLScript(Main.getDirectoryFileName(DIR_DB_MYSQL, DB_DATA_REFRESH_SQL));
        } else if (this.dbType == DatabaseType.PSQL) {
            this.databaseRepository.executeSQLScript(Main.getDirectoryFileName(DIR_DB_PSQL, DB_DATA_REFRESH_SQL));
        } else if (this.dbType == DatabaseType.MONGODB) {
            this.databaseRepository.createIndexesMongoDB(this.indexId);
        }
    }

    /**
     * Shutdown the executor gracefully with progressive timeouts.
     * First attempts immediate termination with a short timeout (30 seconds).
     * If that fails, progressively increases timeout up to 10 minutes before forcing shutdown.
     */
    private void shutdownExecutorGracefully() {
        this.storeExecutor.shutdown();
        LOG.log(Level.INFO, "Virtual thread executor shutdown requested, waiting for pending tasks...");

        long[] timeouts = {
            EXECUTOR_SHUTDOWN_TIMEOUT_30_SECONDS,
            EXECUTOR_SHUTDOWN_TIMEOUT_60_SECONDS,
            EXECUTOR_SHUTDOWN_TIMEOUT_300_SECONDS,
            EXECUTOR_SHUTDOWN_TIMEOUT_600_SECONDS
        };
        TimeUnit unit = TimeUnit.SECONDS;

        for (int i = 0; i < timeouts.length; i++) {
            try {
                if (this.storeExecutor.awaitTermination(timeouts[i], unit)) {
                    LOG.log(Level.INFO, "Virtual thread executor terminated gracefully after {0} seconds", timeouts[i]);
                    return;
                }

                int queueSize = this.storeExecutor.getQueue().size();
                LOG.log(Level.INFO, "Waiting for executor termination (attempt {0}/{1}): {2} tasks remaining",
                    new Object[]{i + 1, timeouts.length, queueSize});
            } catch (InterruptedException e) {
                LOG.log(Level.WARNING, "Virtual thread executor shutdown was interrupted", e);
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Force shutdown if graceful shutdown failed
        LOG.log(Level.WARNING, "Virtual thread executor did not terminate within timeout, forcing shutdown");
        this.storeExecutor.shutdownNow();
        try {
            if (!this.storeExecutor.awaitTermination(EXECUTOR_FINAL_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.log(Level.SEVERE, "Virtual thread executor did not respond to shutdownNow within {0} seconds", EXECUTOR_FINAL_SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            LOG.log(Level.SEVERE, "Interrupted while waiting for executor to respond to shutdownNow", e);
            Thread.currentThread().interrupt();
        }
    }


    /**
     * Scan maven index files.
     *
     * @throws IOException Exception
     */
    @SuppressWarnings("java:S3776") // Cognitive Complexity of methods should not be too high
    private void stepScan() throws IOException {
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

            for (ChunkReader chunkReader : indexReader) {
                LOG.log(Level.INFO,"  chunkName=" + chunkReader.getName());
                LOG.log(Level.INFO,"  chunkVersion=" + chunkReader.getVersion());
                LOG.log(Level.INFO,"  chunkPublished=" + chunkReader.getTimestamp());

                // List one by one all recordsin the chunk
                final RecordExpander recordExpander = new RecordExpander();
                long recordSeq = 0;
                for (Map<String, String> rec : chunkReader) {
                    recordSeq++;
                    final org.apache.maven.index.reader.Record record = recordExpander.apply(rec);
                    Document jsonDoc = new Document(DatabaseRepository.JSON_FIELD_ID, recordSeq);
                    record.getExpanded().forEach((k, v) -> {
                        if (k.getProto().equals(String.class)) {
                            jsonDoc.append(k.getName(), record.getString(k));
                        } else if (k.getProto().equals(String[].class)) {
                            List<String> stringList = new ArrayList<>();
                            for (String s : record.getStringArray(k)) {
                                stringList.add(s);
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

                    String versionString = record.getString(org.apache.maven.index.reader.Record.VERSION);
                    if (versionString == null || versionString.isBlank()) {
                        LOG.log(Level.WARNING, "Record without version found, skipping: {0}", record);
                        continue;
                    }
                    VersionAnalyser analyzedVersion = new VersionAnalyser(versionString);
                    jsonDoc.append(VersionAnalyser.KEY_MAJOR_VERSION, analyzedVersion.getMajorVersion());
                    jsonDoc.append(VersionAnalyser.KEY_VERSION_SEQ, analyzedVersion.getVersionSeq());

                    this.dataToBeStored.add(jsonDoc);
                    this.store(false, recordSeq);
                }
                this.store(true, recordSeq);
            }
        }
    }

    /**
     * Avoid overloading the store executor by waiting when the queue size exceeds maxQueueSize.
     *
     * @param maxQueueSize Maximum allowed queue size before pausing submissions
     * @param resumeQueueSize Queue size to resume submissions
     */
    private void avoidOverload(int maxQueueSize, int resumeQueueSize) {
        // If the store executor queue is too long, wait for it to reduce
        if (this.storeExecutor.getQueue().size() > maxQueueSize) {
            LOG.log(Level.WARNING, "Store executor queue size is large: {0}, waiting for space...", this.storeExecutor.getQueue().size());
            while (this.storeExecutor.getQueue().size() > resumeQueueSize) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            LOG.log(Level.INFO, "Store executor queue size reduced to: {0}, resuming submission", this.storeExecutor.getQueue().size());
        }
    }

    /**
     * Store to database.
     *
     * @param force Flag to force save or not
     * @param counter Record counter
     */
    private void store(final boolean force, final long counter) {
        // Nothing to be saved
        if (this.dataToBeStored.isEmpty()) {
            return;
        }

        if (this.dbType == DatabaseType.MYSQL || this.dbType == DatabaseType.PSQL) {
            int batchSize = this.dbType == DatabaseType.MYSQL ? this.configMgr.getMysqlBatchSize() : this.configMgr.getPsqlBatchSize();

            // Save mysqlBatchSize records as a group,
            // Or when force save, save it no matter of the size
            if (this.dataToBeStored.size() >= batchSize || force) {
                // The maxQueueSize will decide the memory usage
                // Example:
                //   256 ~= 15 GB memory usage
                //   128 ~= 7.8 GB memory usage
                this.avoidOverload(SQL_QUEUE_MAX_SIZE, SQL_QUEUE_RESUME_SIZE);

                // Submit store operation to virtual thread for asynchronous execution.
                List<Document> docsToStore = List.copyOf(this.dataToBeStored);
                this.storeExecutor.submit(() -> {
                    this.databaseRepository.storeSQL(docsToStore, counter);
                });

                // Clear the Cached Object
                this.dataToBeStored.clear();
            }
        } else if (this.dbType == DatabaseType.MONGODB) {
            // Save mongodbBatchSize records as a group,
            // Or when force save, save it no matter of the size
            if (this.dataToBeStored.size() >= this.configMgr.getMongodbBatchSize() || force) {
                this.avoidOverload(MONGODB_QUEUE_MAX_SIZE, MONGODB_QUEUE_RESUME_SIZE);

                List<Document> docsToStore = List.copyOf(this.dataToBeStored);
                this.storeExecutor.submit(() -> {
                    this.databaseRepository.storeMongoDB(docsToStore, counter, this.indexId);
                });

                // Clear the Cached Object
                this.dataToBeStored.clear();
            }
        }
    }
}
