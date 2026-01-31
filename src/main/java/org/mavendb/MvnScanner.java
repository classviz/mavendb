package org.mavendb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Indexes;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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

/**
 * Scan all artifacts in maven repository.
 *
 * @see <a href="https://github.com/apache/maven-indexer/blob/master/indexer-reader/src/test/java/org/apache/maven/index/reader/IndexReaderTest.java">IndexReaderTest</a>
 */
public class MvnScanner implements AutoCloseable {

    /**
     * SQL script to create schema.
     */
    private static final String DB_CREATE_SQL = "create.sql";

    /**
     * SQL script to refresh data.
     */
    private static final String DB_DATA_REFRESH_SQL = "data-refresh.sql";

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

    /**
     * Record representing a Maven artifact from the index.
     * Uses a compact constructor to create a defensive copy of the mutable Document object.
     */
    public record MvnRecord(Long seqid, Integer majorVersion, Long versionSeq, Document json) {
        /**
         * Compact constructor that creates a defensive copy of the mutable Document.
         * This prevents external code from modifying the Document after the record is created.
         */
        public MvnRecord {
            if (json != null) {
                // Create a defensive copy of the Document to prevent external mutation
                json = new Document(json);
            }
        }

        /**
         * Returns a defensive copy of the json Document to prevent external mutation.
         *
         * @return a copy of the json Document, or null if json is null
         */
        @Override
        public Document json() {
            return json == null ? null : new Document(json);
        }
    }

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

    /**
     * Database repository for storing records and documents.
     */
    private DatabaseRepository databaseRepository;

    /* ------- MySQL ------- */

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

    private String mysqlURL = ConfigurationManager.DEFAULT_MYSQL_URL;

    /**
     * Objects to be saved to DB.
     */
    private List<MvnRecord> sqlDataList = new ArrayList<>();

    /**
     * Batch size for MySQL operations.
     */
    private int mysqlBatchSize;

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

    /* ------- PSQL ------- */

    private String psqlURL = ConfigurationManager.DEFAULT_PSQL_URL;

    private static final Properties PSQL_CONNECTION_PROPS = new Properties();
    static {
        PSQL_CONNECTION_PROPS.setProperty("ssl", "false");
    }

    /**
     * Batch size for PSQL operations.
     */
    private int psqlBatchSize;

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
        ConfigurationManager configMgr = new ConfigurationManager(config);

        // Load MySQL configurations
        this.mysqlURL = configMgr.getMysqlUrl();
        String mysqlUser = configMgr.getDatabaseUser(ConfigurationManager.getConfigMysqlUser(), DatabaseType.MYSQL);
        String mysqlPassword = configMgr.getDatabasePassword(ConfigurationManager.getConfigMysqlPassword(), DatabaseType.MYSQL);
        MYSQL_CONNECTION_PROPS.setProperty("user", mysqlUser);
        MYSQL_CONNECTION_PROPS.setProperty("password", mysqlPassword);
        this.mysqlBatchSize = configMgr.getMysqlBatchSize();

        // Load PSQL configurations
        this.psqlURL = configMgr.getPsqlUrl();
        String psqlUser = configMgr.getDatabaseUser(ConfigurationManager.getConfigPsqlUser(), DatabaseType.PSQL);
        String psqlPassword = configMgr.getDatabasePassword(ConfigurationManager.getConfigPsqlPassword(), DatabaseType.PSQL);
        PSQL_CONNECTION_PROPS.setProperty("user", psqlUser);
        PSQL_CONNECTION_PROPS.setProperty("password", psqlPassword);
        this.psqlBatchSize = configMgr.getPsqlBatchSize();

        // Load MongoDB configurations
        this.mongodbBatchSize = configMgr.getMongodbBatchSize();

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
            this.stepExecuteSQLScript(this.mysqlURL, MYSQL_CONNECTION_PROPS, Main.getDirectoryFileName(DIR_DB_MYSQL, DB_CREATE_SQL));
            this.databaseRepository = new DatabaseRepository(DatabaseType.MYSQL, this.mysqlURL, MYSQL_CONNECTION_PROPS);
        } else if (this.dbType == DatabaseType.MONGODB) {
            String mongoUrl = configMgr.getMongodbUrl();
            this.mongoClient = MongoClients.create(mongoUrl);
            this.mongoDatabase = configMgr.getMongodbDatabase();
            this.databaseRepository = new DatabaseRepository(this.mongoClient, this.mongoDatabase, this.indexId);
        } else if (this.dbType == DatabaseType.PSQL) {
            this.stepExecuteSQLScript(this.psqlURL, PSQL_CONNECTION_PROPS, Main.getDirectoryFileName(DIR_DB_PSQL, DB_CREATE_SQL));
            this.databaseRepository = new DatabaseRepository(DatabaseType.PSQL, this.psqlURL, PSQL_CONNECTION_PROPS);
        }

        long start = System.currentTimeMillis();
        this.stepScan();

        // Shutdown virtual thread executor and wait for pending tasks with exponential backoff
        shutdownExecutorGracefully();
        LOG.log(Level.INFO, "Scan execution time={0}", System.currentTimeMillis() - start);

        // Refresh Data
        if (this.dbType == DatabaseType.MYSQL) {
            this.stepExecuteSQLScript(this.mysqlURL, MYSQL_CONNECTION_PROPS, Main.getDirectoryFileName(DIR_DB_MYSQL, DB_DATA_REFRESH_SQL));
        } else if (this.dbType == DatabaseType.PSQL) {
            this.stepExecuteSQLScript(this.psqlURL, PSQL_CONNECTION_PROPS, Main.getDirectoryFileName(DIR_DB_PSQL, DB_DATA_REFRESH_SQL));
        } else if (this.dbType == DatabaseType.MONGODB) {
            this.createIndexesMongoDB();
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
     * Execute an SQL script.
     *
     * @see <a href="https://wiki.eclipse.org/EclipseLink/Examples/JPA/EMAPI#Getting_a_JDBC_Connection_from_an_EntityManager">Getting a JDBC Connection from an EntityManager</a>
     */
    private void stepExecuteSQLScript(String url, Properties props, String script) throws IOException, SQLException {
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
            this.sqlDataList.add(new MvnRecord(recordSeq,
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
        if (this.dbType == DatabaseType.MYSQL || this.dbType == DatabaseType.PSQL) {
            // Nothing to be saved
            if (this.sqlDataList.isEmpty()) {
                return;
            }

            int batchSize = this.dbType == DatabaseType.MYSQL ? this.mysqlBatchSize : this.psqlBatchSize;

            // Save mysqlBatchSize records as a group,
            // Or when force save, save it no matter of the size
            if (this.sqlDataList.size() >= batchSize || force) {
                // The maxQueueSize will decide the memory usage
                // Example:
                //   256 ~= 15 GB memory usage
                //   128 ~= 7.8 GB memory usage
                this.avoidOverload(SQL_QUEUE_MAX_SIZE, SQL_QUEUE_RESUME_SIZE);

                // Submit store operation to virtual thread for asynchronous execution.
                List<MvnRecord> recordsToStore = List.copyOf(this.sqlDataList);
                this.storeExecutor.submit(() -> {
                    this.databaseRepository.storeSQL(recordsToStore, counter);
                });

                // Clear the Cached Object
                this.sqlDataList.clear();
            }
        } else if (this.dbType == DatabaseType.MONGODB) {
            // Nothing to be saved
            if (this.mongoDocList.isEmpty()) {
                return;
            }

            // Save mongodbBatchSize records as a group,
            // Or when force save, save it no matter of the size
            if (this.mongoDocList.size() >= this.mongodbBatchSize || force) {
                this.avoidOverload(MONGODB_QUEUE_MAX_SIZE, MONGODB_QUEUE_RESUME_SIZE);

                List<Document> docsToStore = List.copyOf(this.mongoDocList);
                this.storeExecutor.submit(() -> {
                    this.databaseRepository.storeMongoDB(docsToStore, counter);
                });

                // Clear the Cached Object
                this.mongoDocList.clear();
            }
        }
    }


    private void createIndexesMongoDB() {
        long start = System.currentTimeMillis();

        this.mongoClient.getDatabase(this.mongoDatabase).getCollection(this.indexId).createIndex(Indexes.compoundIndex(
            Indexes.ascending("groupId"),
            Indexes.ascending("artifactId"),
            Indexes.ascending("version"),
            Indexes.ascending("versionSeq"),
            Indexes.ascending("majorVersion")
        ));
        LOG.log(Level.INFO, "MongoDB createIndex finished, execution time {0} ms", new Object[]{System.currentTimeMillis() - start});
    }

    @Override
    public void close() {
        if (mongoClient != null) mongoClient.close();
    }
}
