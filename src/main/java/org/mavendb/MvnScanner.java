package org.mavendb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Persistence;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;
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

    /** Logger. */
    private static final Logger LOG = Logger.getLogger(MvnScanner.class.getName());
    private static final String ENTITY_MANAGER_FACTORY = "PUMvn";

    private final URI indexFolder;
    private final DatabaseType dbType;
    /**
     * Maven repo Index ID.
     * The value is the property "nexus.index.id" in nexus-maven-repository-index.properties file.
     * Example: central.
     */
    private String indexId;

    /* ------- MySQL ------- */

    /**
     * Database store factory.
     */
    private EntityManagerFactory mysqlEmf;

    /**
     * Objects to be saved to DB.
     */
    private List<MvnRecord> mysqlList = new ArrayList<>();

    /**
     * Batch size for MySQL operations.
     */
    private int batchSizeMysql;

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
    private int batchSizeMongodb;

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
        if (StringUtils.isBlank(folderPath)) {
            throw new IllegalArgumentException("Index folder path cannot be null or empty");
        }

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

    public void perform(Properties config) throws IOException {
        // Load batch size configurations
        this.batchSizeMysql = Integer.parseInt(config.getProperty("batch.size.mysql", "1000000"));
        this.batchSizeMongodb = Integer.parseInt(config.getProperty("batch.size.mongodb", "2000000"));

        if (this.dbType == DatabaseType.MYSQL) {
            this.mysqlEmf = Persistence.createEntityManagerFactory(ENTITY_MANAGER_FACTORY, config);
            this.stepExecuteSQLScript(Main.getDirectoryFileName(Main.DIR_DB_MYSQL, Main.DB_MYSQL_CREATE_SQL));
        } else if (this.dbType == DatabaseType.MONGODB) {
            this.mongoClient = MongoClients.create(config.getProperty("com.mongodb.client.uri"));
            this.mongoDatabase = config.getProperty("com.mongodb.database.name", "mavendb");
        }

        long start = System.currentTimeMillis();
        this.stepScan();
        LOG.log(Level.INFO, "Scan execution time={0}", System.currentTimeMillis() - start);

        // Refresh Data
        if (this.dbType == DatabaseType.MYSQL) {
            this.stepExecuteSQLScript(Main.getDirectoryFileName(Main.DIR_DB_MYSQL, Main.DB_MYSQL_DATA_REFRESH_SQL));
        }
    }

    /**
     * Execute an SQL script.
     *
     * @see <a href="https://wiki.eclipse.org/EclipseLink/Examples/JPA/EMAPI#Getting_a_JDBC_Connection_from_an_EntityManager">Getting a JDBC Connection from an EntityManager</a>
     */
    private void stepExecuteSQLScript(String script) throws IOException{
        try (EntityManager em = mysqlEmf.createEntityManager();
             Reader r = new FileReader(script, StandardCharsets.UTF_8)
        ) {
            em.getTransaction().begin();

            LOG.log(Level.INFO, "SQL {0} execution started", script);
            long start = System.currentTimeMillis();
            ScriptRunner sr = new ScriptRunner(em.unwrap(Connection.class));
            sr.runScript(r);
            LOG.log(Level.INFO, "SQL {0} execution finished, execution time {1} ms", new Object[]{script, System.currentTimeMillis() - start});

            em.getTransaction().commit();
        } catch (Exception e) {
            throw new RuntimeException("SQL script execution failed: " + script, e);
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
                    if (StringUtils.isBlank(versionString)) {
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
        if (this.dbType == DatabaseType.MYSQL) {
            // Prepare the Entity Object
            MvnRecord dbAi = new MvnRecord(recordSeq);
            dbAi.setMajorVersion(analizedVersion.getMajorVersion());
            dbAi.setVersionSeq(BigInteger.valueOf(analizedVersion.getVersionSeq()));
            dbAi.setJson(jsonDocument.toJson());

            // Add to DB To be saved List
            this.mysqlList.add(dbAi);
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
     */
    private void store(final boolean force, final long counter) throws IOException {
        if (this.dbType == DatabaseType.MYSQL) {
            // Nothing to be saved
            if (this.mysqlList.isEmpty()) {
                return;
            }

            // Save batchSizeMysql records as a group,
            // Or when force save, save it no matter of the size
            if (this.mysqlList.size() >= this.batchSizeMysql || force) {
                try (EntityManager em = this.mysqlEmf.createEntityManager()) {
                    LocalDateTime begin = LocalDateTime.now();
                    em.setFlushMode(FlushModeType.COMMIT);
                    em.getTransaction().begin();
                    this.mysqlList.forEach(em::persist);
                    em.getTransaction().commit();

                    Duration duration = Duration.between(begin, LocalDateTime.now());
                    LOG.log(Level.INFO, "persist finished for records counter={0} in seconds={1}", new Object[]{counter, duration.toSeconds()});
                }

                // Clear the Cached Object
                this.mysqlList.clear();
            }
        } else if (this.dbType == DatabaseType.MONGODB) {
            // Nothing to be saved
            if (this.mongoDocList.isEmpty()) {
                return;
            }

            // Save batchSizeMongodb records as a group,
            // Or when force save, save it no matter of the size
            if (this.mongoDocList.size() >= this.batchSizeMongodb || force) {
                LocalDateTime begin = LocalDateTime.now();
                this.mongoClient.getDatabase(this.mongoDatabase).getCollection(this.indexId).insertMany(this.mongoDocList);

                Duration duration = Duration.between(begin, LocalDateTime.now());
                LOG.log(Level.INFO, "MongoDB persist finished for records counter={0} in seconds={1}", new Object[]{counter, duration.toSeconds()});

                // Clear the Cached Object
                this.mongoDocList.clear();
            }
        }
    }

    @Override
    public void close() {
        if (mysqlEmf != null) mysqlEmf.close();
        if (mongoClient != null) mongoClient.close();
    }
}
