package org.mavendb;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.bson.Document;
import org.mavendb.Main.DatabaseType;
import org.postgresql.util.PGobject;

/**
 * Repository for database operations with support for MySQL, PostgreSQL, and MongoDB.
 * Handles all persistent storage operations for Maven records and documents.
 */
class DatabaseRepository {

    /** Logger. */
    private static final Logger LOG = Logger.getLogger(DatabaseRepository.class.getName());

    protected static final String JSON_FIELD_ID = "_id";

    protected static final List<DatabaseType> SUPPORTED_SQL_DB_TYPES = List.of(
        DatabaseType.MYSQL,
        DatabaseType.PSQL,
        DatabaseType.SQLITE
    );

    /* ------- SQL Field Indices ------- */
    private static final int SQL_IDX_SEQID = 1;
    private static final int SQL_IDX_MAJOR_VERSION = 2;
    private static final int SQL_IDX_VERSION_SEQ = 3;
    private static final int SQL_IDX_RECORD_MODIFIED = 4;
    private static final int SQL_IDX_FILE_MODIFIED = 5;
    private static final int SQL_IDX_FILE_SIZE = 6;
    private static final int SQL_IDX_HAS_SIGNATURE = 7;
    private static final int SQL_IDX_HAS_SOURCES = 8;
    private static final int SQL_IDX_HAS_JAVADOC = 9;
    private static final int SQL_IDX_SHA1 = 10;
    private static final int SQL_IDX_GROUP_ID = 11;
    private static final int SQL_IDX_ARTIFACT_ID = 12;
    private static final int SQL_IDX_ARTIFACT_VERSION = 13;
    private static final int SQL_IDX_CLASSIFIER = 14;
    private static final int SQL_IDX_PACKAGING = 15;
    private static final int SQL_IDX_FILE_EXTENSION = 16;
    private static final int SQL_IDX_NAME = 17;
    private static final int SQL_IDX_DESCRIPTION = 18;
    private static final int SQL_IDX_JSON = 19;

    private static final int SHA1_MAX_LENGTH = 40;

    private final DatabaseType dbType;
    private final String dbUrl;
    private final Properties sqlConnectionProps;

    /**
     * Constructor for SQL-based repositories (MySQL/PostgreSQL).
     *
     * @param dbType Database type (MYSQL or PSQL)
     * @param sqlUrl JDBC connection URL
     * @param sqlConnectionProps Connection properties (will be defensively copied)
     */
    protected DatabaseRepository(DatabaseType dbType, String sqlUrl, Properties sqlConnectionProps) {
        this.dbType = dbType;
        this.dbUrl = sqlUrl;
        // Create a defensive copy to prevent external mutation of connection properties
        this.sqlConnectionProps = new Properties();
        if (sqlConnectionProps != null) {
            this.sqlConnectionProps.putAll(sqlConnectionProps);
        }
    }

    /**
     * Constructor for MongoDB repository.
     *
     * @param mongoUrl MongoDB connection URL
     */ 
    protected DatabaseRepository(String mongoUrl) {
        this(DatabaseType.MONGODB, mongoUrl, null);
    }

    /**
     * Execute an SQL script.
     */
    protected void executeSQLScript(String script) throws IOException, SQLException {
        if (SUPPORTED_SQL_DB_TYPES.contains(this.dbType)) {
            // Directory for DB scripts are in the folder of "db/<dbtype>"
            try (Connection conn = DriverManager.getConnection(this.dbUrl, this.sqlConnectionProps);
                Reader r = new FileReader(
                    Main.getDirectoryFileName("db" + File.separator + this.dbType.name().toLowerCase(), script),
                    StandardCharsets.UTF_8)
            ) {
                long start = System.currentTimeMillis();
                LOG.log(Level.INFO, "SQL {0} execution started", script);
                conn.setAutoCommit(false);
                new ScriptRunner(conn).runScript(r);
                LOG.log(Level.INFO, "SQL {0} execution finished, execution time {1} ms", new Object[]{script, System.currentTimeMillis() - start});
            }
        } else {
            throw new UnsupportedOperationException("SQL script execution is not supported for database type: " + this.dbType);
        }
    }

    /**
     * Store SQL records (MySQL/PostgreSQL) to database.
     *
     * @param storeList List of records to persist (independent copy, not shared)
     * @param counter Record counter for logging
     */
    @SuppressFBWarnings(value = "VA_FORMAT_STRING_USES_NEWLINE", justification = "False positive for Java text blocks")
    protected void storeSQL(List<Document> storeList, final long counter) {
        try (Connection conn = DriverManager.getConnection(this.dbUrl, this.sqlConnectionProps)) {
            LocalDateTime begin = LocalDateTime.now();
            conn.setAutoCommit(false);

            String tableName = this.dbType == DatabaseType.PSQL ? "mavendb.gav" : "gav";
            String sqlGav = """
                INSERT INTO %s (
                    seqid,
                    major_version, version_seq,
                    record_modified, file_modified, file_size,
                    has_signature, has_sources, has_javadoc,
                    sha1,
                    group_id, artifact_id, artifact_version,
                    classifier, packaging, file_extension,
                    name, description,
                    json
                ) VALUES (
                    ?,
                    ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?,
                    ?
                )
            """.formatted(tableName);

            try (PreparedStatement pstmt = conn.prepareStatement(sqlGav)) {
                for (Document record : storeList) {
                    bindSQLParameters(pstmt, record);
                    pstmt.addBatch();
                }
                // Execute remaining batch
                pstmt.executeBatch();
                conn.commit();
            }
            Duration duration = Duration.between(begin, LocalDateTime.now());
            LOG.log(Level.INFO, "persist finished for records counter={0} in seconds={1}, batchSize={2}",
                    new Object[]{counter, duration.toSeconds(), storeList.size()});
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Error during SQL persist operation for records counter=" + counter, e);
        }
    }

    /**
     * Bind Maven record parameters to prepared statement with database-specific handling.
     *
     * @param pstmt Prepared statement
     * @param record Maven record to bind
     * @throws SQLException if binding fails
     */
    protected void bindSQLParameters(PreparedStatement pstmt, Document record) throws SQLException {
        pstmt.setLong(SQL_IDX_SEQID, record.getLong(JSON_FIELD_ID));

        pstmt.setInt(SQL_IDX_MAJOR_VERSION, record.getInteger(VersionAnalyser.KEY_MAJOR_VERSION));
        pstmt.setLong(SQL_IDX_VERSION_SEQ, record.getLong(VersionAnalyser.KEY_VERSION_SEQ));

        pstmt.setObject(SQL_IDX_RECORD_MODIFIED, record.getLong("recordModified"));
        record.remove("recordModified");
        pstmt.setObject(SQL_IDX_FILE_MODIFIED, record.getLong("fileModified"));
        record.remove("fileModified");
        pstmt.setObject(SQL_IDX_FILE_SIZE, record.getLong("fileSize"));
        record.remove("fileSize");

        pstmt.setBoolean(SQL_IDX_HAS_SIGNATURE, record.getBoolean("hasSignature"));
        record.remove("hasSignature");
        pstmt.setBoolean(SQL_IDX_HAS_SOURCES, record.getBoolean("hasSources"));
        record.remove("hasSources");
        pstmt.setBoolean(SQL_IDX_HAS_JAVADOC, record.getBoolean("hasJavadoc"));
        record.remove("hasJavadoc");

        // Shrink SHA1 field to maximum length of 40 if needed
        String sha1Value = strip(record.getString("sha1"));
        if (sha1Value != null && sha1Value.length() > SHA1_MAX_LENGTH) {
            sha1Value = sha1Value.substring(0, SHA1_MAX_LENGTH);
            LOG.warning("SHA1 value truncated to 40 characters: " + sha1Value + " for record " + record);
        }
        pstmt.setString(SQL_IDX_SHA1, sha1Value);
        record.remove("sha1");

        pstmt.setString(SQL_IDX_GROUP_ID, strip(record.getString("groupId")));
        record.remove("groupId");
        pstmt.setString(SQL_IDX_ARTIFACT_ID, strip(record.getString("artifactId")));
        record.remove("artifactId");
        pstmt.setString(SQL_IDX_ARTIFACT_VERSION, strip(record.getString("version")));
        record.remove("version");

        pstmt.setString(SQL_IDX_CLASSIFIER, strip(record.getString("classifier")));
        record.remove("classifier");
        pstmt.setString(SQL_IDX_PACKAGING, strip(record.getString("packaging")));
        record.remove("packaging");
        pstmt.setString(SQL_IDX_FILE_EXTENSION, strip(record.getString("fileExtension")));
        record.remove("fileExtension");

        pstmt.setString(SQL_IDX_NAME, strip(record.getString("name")));
        record.remove("name");
        pstmt.setString(SQL_IDX_DESCRIPTION, strip(record.getString("description")));
        record.remove("description");

        // Remove _id from json if it's the only field left
        if (record.size() == 1) {
            record.remove(JSON_FIELD_ID);
        }

        // Handle JSON field with database-specific types
        if (dbType == DatabaseType.MYSQL) {
            if (record.size() == 0) {
                pstmt.setString(SQL_IDX_JSON, null);
            } else {
                pstmt.setString(SQL_IDX_JSON, record.toJson());
            }
        } else if (dbType == DatabaseType.PSQL) {
            if (record.size() == 0) {
                pstmt.setObject(SQL_IDX_JSON, null, java.sql.Types.OTHER);
            } else {
                PGobject jsonObject = new PGobject();
                jsonObject.setType("jsonb");
                jsonObject.setValue(record.toJson());
                pstmt.setObject(SQL_IDX_JSON, jsonObject);
            }
        } else if (dbType == DatabaseType.SQLITE) {
            // SQLite stores JSON as TEXT
            if (record.size() == 0) {
                pstmt.setString(SQL_IDX_JSON, null);
            } else {
                pstmt.setString(SQL_IDX_JSON, record.toJson());
            }
        }
    }

    /**
     * Strip leading/trailing whitespace and double quotes from input string.
     */
    private String strip(String input) {
        return StringUtils.strip(StringUtils.stripToNull(input), "\"");
    }

    /**
     * Store MongoDB documents to database.
     *
     * @param storeDocuments Documents to persist
     * @param counter Record counter for logging
     */
    protected void storeMongoDB(List<Document> storeDocuments, final long counter, final String mongoIndexId) {
        LocalDateTime begin = LocalDateTime.now();
        try (MongoClient mongoClient = MongoClients.create(this.dbUrl)) {
            mongoClient.getDatabase(ConfigurationManager.DATABASE_NAME).getCollection(mongoIndexId).insertMany(
                storeDocuments,
                new InsertManyOptions().ordered(false)
            );
        }
        Duration duration = Duration.between(begin, LocalDateTime.now());
        LOG.log(Level.INFO, "MongoDB persist finished for position={0} in seconds={1} Millis={2}, batchSize={3}",
                new Object[]{counter, duration.toSeconds(), duration.toMillis(), storeDocuments.size()});
    }

    protected void createIndexesMongoDB(String indexId) {
        long start = System.currentTimeMillis();
        try (MongoClient mongoClient = MongoClients.create(this.dbUrl)) {
            mongoClient.getDatabase(ConfigurationManager.DATABASE_NAME).getCollection(indexId).createIndex(Indexes.compoundIndex(
                Indexes.ascending("groupId"),
                Indexes.ascending("artifactId"),
                Indexes.ascending("version"),
                Indexes.ascending("versionSeq"),
                Indexes.ascending("majorVersion")
            ));
        }
        LOG.log(Level.INFO, "MongoDB createIndex finished, execution time {0} ms", new Object[]{System.currentTimeMillis() - start});
    }
}
