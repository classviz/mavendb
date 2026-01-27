package org.mavendb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.maven.index.reader.ChunkReader;
import org.apache.maven.index.reader.IndexReader;
import org.apache.maven.index.reader.RecordExpander;
import org.apache.maven.index.reader.ResourceHandler;
import org.apache.maven.index.reader.WritableResourceHandler;
import org.apache.maven.index.reader.resource.PathWritableResourceHandler;
import org.apache.maven.index.reader.resource.UriResourceHandler;
import org.bson.Document;
import org.mavendb.Main.DatabaseType;
import org.postgresql.util.PGobject;

/**
 * Scan all artifacts in maven repository.
 *
 * @see <a href="https://github.com/apache/maven-indexer/blob/master/indexer-reader/src/test/java/org/apache/maven/index/reader/IndexReaderTest.java">IndexReaderTest</a>
 */
public class MvnScanner implements AutoCloseable {

    private record MvnRecord(Long seqid, Integer majorVersion, Long versionSeq, Document json) {}

    /** Logger. */
    private static final Logger LOG = Logger.getLogger(MvnScanner.class.getName());

    private final URI indexFolder;
    private final DatabaseType dbType;
    /**
     * Maven repo Index ID.
     * The value is the property "nexus.index.id" in nexus-maven-repository-index.properties file.
     * Example: central.
     */
    private String indexId;

    /**
     * Virtual thread executor for asynchronous store operations.
     * Uses Java virtual threads (Project Loom) with configurable concurrency limit.
     */
    private ThreadPoolExecutor storeExecutor;

    /* ------- MySQL ------- */

    private String MYSQL_URL = "jdbc:mysql://localhost:3306/mavendb";

    private static final Properties MYSQL_CONNECTION_PROPS = new Properties();

    static {
        MYSQL_CONNECTION_PROPS.setProperty("allowPublicKeyRetrieval", "true");
        MYSQL_CONNECTION_PROPS.setProperty("cachePrepStmts", "true");
        MYSQL_CONNECTION_PROPS.setProperty("rewriteBatchedStatements", "true");
        MYSQL_CONNECTION_PROPS.setProperty("useCompression", "true");
        MYSQL_CONNECTION_PROPS.setProperty("useLocalSessionState", "true");
        MYSQL_CONNECTION_PROPS.setProperty("useServerPrepStmts", "true");
        MYSQL_CONNECTION_PROPS.setProperty("useSSL", "false");
        MYSQL_CONNECTION_PROPS.setProperty("zeroDateTimeBehavior", "CONVERT_TO_NULL");
    }

    /**
     * Objects to be saved to DB.
     */
    private List<MvnRecord> mySqlpSqlList = new ArrayList<>();

    /**
     * Batch size for MySQL operations.
     */
    private int mysqlBatchSize;
    private int mysqlBatchWriteSize;

    /* ------- MongoDB ------- */

    /**
     * MongoDB client for storing documents.
     */
    private MongoClient mongoClient;

    /**
     * MongoDB database name.
     */
    private String mongoDatabase;

    /**
     * MongoDB documents to be saved to DB.
     */
    private List<Document> mongoDocList = new ArrayList<>();

    /**
     * Batch size for MongoDB operations.
     */
    private int mongodbBatchSize;

    /* ------- PostgreSQL ------- */

    private String PSQL_URL = "jdbc:postgresql://localhost:5432/mavendb";

    private static final Properties POSTGRESQL_CONNECTION_PROPS = new Properties();
    static {
        POSTGRESQL_CONNECTION_PROPS.setProperty("ssl", "false");
    }

    /**
     * Batch size for PostgreSQL operations.
     */
    private int psqlBatchSize;
    private int psqlBatchWriteSize;

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
        // Load MySQL configurations
        MYSQL_URL = config.getProperty("mavendb.mysql.url", MYSQL_URL);
        MYSQL_CONNECTION_PROPS.setProperty("user", config.getProperty("mavendb.mysql.user"));
        MYSQL_CONNECTION_PROPS.setProperty("password", config.getProperty("mavendb.mysql.password"));
        this.mysqlBatchSize = Integer.parseInt(config.getProperty("mavendb.mysql.batch.size", "10000"));
        this.mysqlBatchWriteSize = Integer.parseInt(config.getProperty("mavendb.mysql.batch.writing.size", "1000"));

        // Load PostgreSQL configurations
        PSQL_URL = config.getProperty("mavendb.psql.url", PSQL_URL);
        POSTGRESQL_CONNECTION_PROPS.setProperty("user", config.getProperty("mavendb.psql.user"));
        POSTGRESQL_CONNECTION_PROPS.setProperty("password", config.getProperty("mavendb.psql.password"));
        this.psqlBatchSize = Integer.parseInt(config.getProperty("mavendb.psql.batch.size", "10000"));
        this.psqlBatchWriteSize = Integer.parseInt(config.getProperty("mavendb.psql.batch.writing.size", "1000"));

        // Load MongoDB configurations
        this.mongodbBatchSize = Integer.parseInt(config.getProperty("mavendb.mongodb.batch.size", "20000"));

        // Load max concurrent threads configuration, default to number of available processors
        // Ensure at least 2 virtual thread
        int maxConcurrentThreads = Math.max(2,
            Integer.parseInt(config.getProperty(
                "thread.pool.size",
                String.valueOf(Runtime.getRuntime().availableProcessors())
            )));
        LOG.log(Level.INFO, "Virtual thread pool size configured: {0}", maxConcurrentThreads);

        // Create bounded virtual thread executor with configured concurrency limit
        this.storeExecutor = new ThreadPoolExecutor(
            0,                                          // Core threads
            maxConcurrentThreads,                       // Max threads
            60,                                         // Keep-alive time
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),                // Unbounded queue for tasks
            Thread.ofVirtual().factory()
        );

        if (this.dbType == DatabaseType.MYSQL) {
            this.stepExecuteSQLScript(this.dbType, Main.getDirectoryFileName(Main.DIR_DB_MYSQL, Main.DB_CREATE_SQL));
        } else if (this.dbType == DatabaseType.MONGODB) {
            this.mongoClient = MongoClients.create(config.getProperty("mavendb.mongodb.url"));
            this.mongoDatabase = config.getProperty("mavendb.mongodb.database.name", "mavendb");
        } else if (this.dbType == DatabaseType.PSQL) {
            this.stepExecuteSQLScript(this.dbType, Main.getDirectoryFileName(Main.DIR_DB_PSQL, Main.DB_CREATE_SQL));
        }

        long start = System.currentTimeMillis();
        this.stepScan();

        // Shutdown virtual thread executor and wait for pending tasks
        this.storeExecutor.shutdown();
        try {
            if (!this.storeExecutor.awaitTermination(5, TimeUnit.MINUTES)) {
                LOG.log(Level.WARNING, "Virtual thread executor did not terminate within timeout, forcing shutdown");
                this.storeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOG.log(Level.SEVERE, "Virtual thread executor shutdown was interrupted", e);
            this.storeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.log(Level.INFO, "Scan execution time={0}", System.currentTimeMillis() - start);

        // Refresh Data
        if (this.dbType == DatabaseType.MYSQL) {
            this.stepExecuteSQLScript(this.dbType, Main.getDirectoryFileName(Main.DIR_DB_MYSQL, Main.DB_DATA_REFRESH_SQL));
        } else if (this.dbType == DatabaseType.PSQL) {
            this.stepExecuteSQLScript(this.dbType, Main.getDirectoryFileName(Main.DIR_DB_PSQL, Main.DB_DATA_REFRESH_SQL));
        }
    }

    /**
     * Execute an SQL script.
     *
     * @see <a href="https://wiki.eclipse.org/EclipseLink/Examples/JPA/EMAPI#Getting_a_JDBC_Connection_from_an_EntityManager">Getting a JDBC Connection from an EntityManager</a>
     */
    private void stepExecuteSQLScript(DatabaseType dbtype, String script) throws IOException, SQLException {
        String url;
        Properties props;
        if (dbtype == DatabaseType.MYSQL) {
            url = MYSQL_URL;
            props = MYSQL_CONNECTION_PROPS;
        } else if (dbtype == DatabaseType.PSQL) {
            url = PSQL_URL;
            props = POSTGRESQL_CONNECTION_PROPS;
        } else {
            throw new IllegalArgumentException("Unsupported database type for SQL script execution: " + dbtype);
        }

        try (Connection conn = DriverManager.getConnection(url, props);
             Reader r = new FileReader(script, StandardCharsets.UTF_8)
        ) {
            long start = System.currentTimeMillis();
            LOG.log(Level.INFO, "SQL {0} execution started", script);
            conn.setAutoCommit(false);
            new ScriptRunner(conn).runScript(r);
            LOG.log(Level.INFO, "SQL {0} execution finished, execution time {1} ms", new Object[]{script, System.currentTimeMillis() - start});
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
                    Document jsonDoc = new Document("_id", recordSeq);
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

                    this.add(jsonDoc, analyzedVersion, recordSeq);
                    this.store(false, recordSeq);
                }
                this.store(true, recordSeq);
            }
        }
    }

    private void add(Document jsonDocument, VersionAnalyser analizedVersion, long recordSeq) {
        if (this.dbType == DatabaseType.MYSQL || this.dbType == DatabaseType.PSQL) {
            // Add to DB To be saved List
            this.mySqlpSqlList.add(new MvnRecord(recordSeq,
                analizedVersion.getMajorVersion(),
                analizedVersion.getVersionSeq(),
                jsonDocument));
        } else if (this.dbType == DatabaseType.MONGODB) {
            // Store jsonObject into MongoDB batch list
            jsonDocument.append("majorVersion", analizedVersion.getMajorVersion());
            jsonDocument.append("versionSeq", analizedVersion.getVersionSeq());
            // Add to MongoDB batch list for batch processing
            this.mongoDocList.add(jsonDocument);
        }
    }

    /**
     * Store to database.
     *
     * @param force Flag to force save or not
     * @param counter Record counter
     */
    private void store(final boolean force, final long counter) {
        if (this.dbType == DatabaseType.MYSQL || this.dbType == DatabaseType.PSQL) {
            // Nothing to be saved
            if (this.mySqlpSqlList.isEmpty()) {
                return;
            }

            int batchSize = this.dbType == DatabaseType.MYSQL ? this.mysqlBatchSize : this.psqlBatchSize;

            // Save mysqlBatchSize records as a group,
            // Or when force save, save it no matter of the size
            if (this.mySqlpSqlList.size() >= batchSize || force) {
                // Submit store operation to virtual thread for asynchronous execution.
                List<MvnRecord> recordsToStore = List.copyOf(this.mySqlpSqlList);

                // If the queue is too long, this call will block until space is available.
                if (this.storeExecutor.getQueue().size() > 256) {
                    LOG.log(Level.WARNING, "Store executor queue size is large: {0}, waiting for space...", this.storeExecutor.getQueue().size());
                    while (this.storeExecutor.getQueue().size() > 128) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    LOG.log(Level.INFO, "Store executor queue size reduced to: {0}, resuming submission", this.storeExecutor.getQueue().size());
                }

                this.storeExecutor.submit(() -> {
                    this.storeSQL(this.dbType, recordsToStore, counter);
                });

                // Clear the Cached Object
                this.mySqlpSqlList.clear();
            }
        } else if (this.dbType == DatabaseType.MONGODB) {
            // Nothing to be saved
            if (this.mongoDocList.isEmpty()) {
                return;
            }

            // Save mongodbBatchSize records as a group,
            // Or when force save, save it no matter of the size
            if (this.mongoDocList.size() >= this.mongodbBatchSize || force) {
                List<Document> docsToStore = List.copyOf(this.mongoDocList);
                this.storeExecutor.submit(() -> {
                    this.storeMongoDB(docsToStore, counter);
                });

                // Clear the Cached Object
                this.mongoDocList.clear();
            }
        }
    }

    /**
     * Store MySQL/PSQL records asynchronously using virtual threads.
     * Each virtual thread has its own EntityManager instance for thread-safe access.
     * No synchronization needed - EntityManagerFactory and connection pool are thread-safe.
     *
     * @param storeList List of records to persist (independent copy, not shared)
     * @param counter Record counter for logging
     */
    private void storeSQL(DatabaseType dbtype, List<MvnRecord> storeList, final long counter) {
        String url = dbtype == DatabaseType.MYSQL ? MYSQL_URL : PSQL_URL;
        Properties props = dbtype == DatabaseType.MYSQL ? MYSQL_CONNECTION_PROPS : POSTGRESQL_CONNECTION_PROPS;
        int batchWriteSize = dbtype == DatabaseType.MYSQL ? this.mysqlBatchWriteSize : this.psqlBatchWriteSize;

        try (Connection conn = DriverManager.getConnection(url, props)) {
            LocalDateTime begin = LocalDateTime.now();
            conn.setAutoCommit(false);

            String sql = "INSERT INTO mavendb.record (seqid, major_version, version_seq, json) VALUES (?, ?, ?, ?)";

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int batchCount = 0;
                for (MvnRecord record : storeList) {
                    pstmt.setLong(1, record.seqid);
                    pstmt.setInt(2, record.majorVersion);
                    pstmt.setLong(3, record.versionSeq);

                    if (dbtype == DatabaseType.MYSQL) {
                        pstmt.setString(4, record.json.toJson());
                    } else if (dbtype == DatabaseType.PSQL) {
                        PGobject jsonObject = new PGobject();
                        jsonObject.setType("jsonb");
                        jsonObject.setValue(record.json.toJson());
                        pstmt.setObject(4, jsonObject);
                    }
                    pstmt.addBatch();

                    // Execute write batch every records to avoid large batches
                    batchCount++;
                    if (batchCount % batchWriteSize == 0) {
                        pstmt.executeBatch();
                        conn.commit();
                    }
                }
                // Execute remaining batch
                pstmt.executeBatch();
                conn.commit();
            }
            Duration duration = Duration.between(begin, LocalDateTime.now());
            LOG.log(Level.INFO, "persist finished for records counter={0} in seconds={1}, batchSize={2}",
                new Object[]{counter, duration.toSeconds(), storeList.size()});
        } catch ( SQLException e) {
            LOG.log(Level.SEVERE, "Error during MySQL persist operation for records counter=" + counter, e);
        }
    }

    private void storeMongoDB(List<Document> storeDocuments, final long counter) {
        LocalDateTime begin = LocalDateTime.now();
        this.mongoClient.getDatabase(this.mongoDatabase).getCollection(this.indexId).insertMany(storeDocuments);
        Duration duration = Duration.between(begin, LocalDateTime.now());
        LOG.log(Level.INFO, "MongoDB persist finished for position={0} in seconds={1} Millis={2}, batchSize={3}",
            new Object[]{counter, duration.toSeconds(), duration.toMillis(), storeDocuments.size()});
    }

    @Override
    public void close() {
        if (mongoClient != null) mongoClient.close();
    }
}
