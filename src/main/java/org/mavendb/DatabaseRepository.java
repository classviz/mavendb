package org.mavendb;

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
import com.mongodb.client.model.InsertManyOptions;
import org.bson.Document;
import org.postgresql.util.PGobject;

/**
 * Repository for database operations with support for MySQL, PostgreSQL, and MongoDB.
 * Handles all persistent storage operations for Maven records and documents.
 */
public class DatabaseRepository {

    /** Logger. */
    private static final Logger LOG = Logger.getLogger(DatabaseRepository.class.getName());

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

    private final Main.DatabaseType dbType;
    private final String sqlUrl;
    private final Properties sqlConnectionProps;
    private final MongoClient mongoClient;
    private final String mongoDatabase;
    private final String mongoIndexId;

    /**
     * Constructor for SQL-based repositories (MySQL/PostgreSQL).
     *
     * @param dbType Database type (MYSQL or PSQL)
     * @param sqlUrl JDBC connection URL
     * @param sqlConnectionProps Connection properties (will be defensively copied)
     */
    public DatabaseRepository(Main.DatabaseType dbType, String sqlUrl, Properties sqlConnectionProps) {
        this.dbType = dbType;
        this.sqlUrl = sqlUrl;
        // Create a defensive copy to prevent external mutation of connection properties
        this.sqlConnectionProps = new Properties();
        if (sqlConnectionProps != null) {
            this.sqlConnectionProps.putAll(sqlConnectionProps);
        }
        this.mongoClient = null;
        this.mongoDatabase = null;
        this.mongoIndexId = null;
    }

    /**
     * Constructor for MongoDB repository.
     *
     * @param mongoClient MongoDB client
     * @param mongoDatabase MongoDB database name
     * @param mongoIndexId Index ID for collection name
     */
    public DatabaseRepository(MongoClient mongoClient, String mongoDatabase, String mongoIndexId) {
        this.dbType = Main.DatabaseType.MONGODB;
        this.mongoClient = mongoClient;
        this.mongoDatabase = mongoDatabase;
        this.mongoIndexId = mongoIndexId;
        this.sqlUrl = null;
        this.sqlConnectionProps = null;
    }

    /**
     * Store SQL records (MySQL/PostgreSQL) to database.
     *
     * @param storeList List of records to persist (independent copy, not shared)
     * @param counter Record counter for logging
     */
    public void storeSQL(List<MvnScanner.MvnRecord> storeList, final long counter) {
        try (Connection conn = DriverManager.getConnection(sqlUrl, sqlConnectionProps)) {
            LocalDateTime begin = LocalDateTime.now();
            conn.setAutoCommit(false);

            String sqlGav = """
                INSERT INTO mavendb.gav (
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
            """;

            try (PreparedStatement pstmt = conn.prepareStatement(sqlGav)) {
                for (MvnScanner.MvnRecord record : storeList) {
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
    private void bindSQLParameters(PreparedStatement pstmt, MvnScanner.MvnRecord record) throws SQLException {
        pstmt.setLong(SQL_IDX_SEQID, record.seqid());

        pstmt.setInt(SQL_IDX_MAJOR_VERSION, record.majorVersion());
        pstmt.setLong(SQL_IDX_VERSION_SEQ, record.versionSeq());

        pstmt.setObject(SQL_IDX_RECORD_MODIFIED, record.json().getLong("recordModified"));
        record.json().remove("recordModified");
        pstmt.setObject(SQL_IDX_FILE_MODIFIED, record.json().getLong("fileModified"));
        record.json().remove("fileModified");
        pstmt.setObject(SQL_IDX_FILE_SIZE, record.json().getLong("fileSize"));
        record.json().remove("fileSize");

        pstmt.setBoolean(SQL_IDX_HAS_SIGNATURE, record.json().getBoolean("hasSignature"));
        record.json().remove("hasSignature");
        pstmt.setBoolean(SQL_IDX_HAS_SOURCES, record.json().getBoolean("hasSources"));
        record.json().remove("hasSources");
        pstmt.setBoolean(SQL_IDX_HAS_JAVADOC, record.json().getBoolean("hasJavadoc"));
        record.json().remove("hasJavadoc");

        // Shrink SHA1 field to maximum length of 40 if needed
        String sha1Value = record.json().getString("sha1");
        if (sha1Value != null && sha1Value.length() > SHA1_MAX_LENGTH) {
            sha1Value = sha1Value.substring(0, SHA1_MAX_LENGTH);
            LOG.warning("SHA1 value truncated to 40 characters: " + sha1Value + " for record " + record);
        }
        pstmt.setString(SQL_IDX_SHA1, sha1Value);
        record.json().remove("sha1");

        pstmt.setString(SQL_IDX_GROUP_ID, record.json().getString("groupId"));
        record.json().remove("groupId");
        pstmt.setString(SQL_IDX_ARTIFACT_ID, record.json().getString("artifactId"));
        record.json().remove("artifactId");
        pstmt.setString(SQL_IDX_ARTIFACT_VERSION, record.json().getString("version"));
        record.json().remove("version");

        pstmt.setString(SQL_IDX_CLASSIFIER, record.json().getString("classifier"));
        record.json().remove("classifier");
        pstmt.setString(SQL_IDX_PACKAGING, record.json().getString("packaging"));
        record.json().remove("packaging");
        pstmt.setString(SQL_IDX_FILE_EXTENSION, record.json().getString("fileExtension"));
        record.json().remove("fileExtension");

        pstmt.setString(SQL_IDX_NAME, record.json().getString("name"));
        record.json().remove("name");
        pstmt.setString(SQL_IDX_DESCRIPTION, record.json().getString("description"));
        record.json().remove("description");

        // Remove _id from json if it's the only field left
        if (record.json().size() == 1) {
            record.json().remove("_id");
        }

        // Handle JSON field with database-specific types
        if (dbType == Main.DatabaseType.MYSQL) {
            if (record.json().size() == 0) {
                pstmt.setString(SQL_IDX_JSON, null);
            } else {
                pstmt.setString(SQL_IDX_JSON, record.json().toJson());
            }
        } else if (dbType == Main.DatabaseType.PSQL) {
            if (record.json().size() == 0) {
                pstmt.setObject(SQL_IDX_JSON, null, java.sql.Types.OTHER);
            } else {
                PGobject jsonObject = new PGobject();
                jsonObject.setType("jsonb");
                jsonObject.setValue(record.json().toJson());
                pstmt.setObject(SQL_IDX_JSON, jsonObject);
            }
        }
    }

    /**
     * Store MongoDB documents to database.
     *
     * @param storeDocuments Documents to persist
     * @param counter Record counter for logging
     */
    public void storeMongoDB(List<Document> storeDocuments, final long counter) {
        LocalDateTime begin = LocalDateTime.now();
        mongoClient.getDatabase(mongoDatabase).getCollection(mongoIndexId).insertMany(
                storeDocuments,
                new InsertManyOptions().ordered(false)
        );
        Duration duration = Duration.between(begin, LocalDateTime.now());
        LOG.log(Level.INFO, "MongoDB persist finished for position={0} in seconds={1} Millis={2}, batchSize={3}",
                new Object[]{counter, duration.toSeconds(), duration.toMillis(), storeDocuments.size()});
    }
}
