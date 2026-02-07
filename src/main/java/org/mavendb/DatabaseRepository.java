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

import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.maven.index.reader.Record;
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
    protected static final String JSON_FIELD_FILE_NAME = "fileName";

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
    private static final int SQL_IDX_FILE_NAME = 17;
    private static final int SQL_IDX_NAME = 18;
    private static final int SQL_IDX_DESCRIPTION = 19;
    private static final int SQL_IDX_BUNDLE_DESCRIPTION = 20;
    private static final int SQL_IDX_BUNDLE_DOCURL = 21;
    private static final int SQL_IDX_BUNDLE_LICENSE = 22;
    private static final int SQL_IDX_BUNDLE_NAME = 23;
    private static final int SQL_IDX_BUNDLE_SYMBOLICNAME = 24;
    private static final int SQL_IDX_BUNDLE_VERSION = 25;
    private static final int SQL_IDX_EXPORT_PACKAGE = 26;
    private static final int SQL_IDX_IMPORT_PACKAGE = 27;
    private static final int SQL_IDX_REQUIRE_BUNDLE = 28;
    private static final int SQL_IDX_EXPORT_SERVICE = 29;
    private static final int SQL_IDX_JSON = 30;

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
                    file_name,
                    name, description,
                    bundle_description, bundle_docurl, bundle_license,
                    bundle_name, bundle_symbolicname, bundle_version,
                    export_package, import_package,
                    require_bundle, export_service,
                    json
                ) VALUES (
                    ?,
                    ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?,
                    ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?,
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
        record.remove(VersionAnalyser.KEY_MAJOR_VERSION);
        pstmt.setLong(SQL_IDX_VERSION_SEQ, record.getLong(VersionAnalyser.KEY_VERSION_SEQ));
        record.remove(VersionAnalyser.KEY_VERSION_SEQ);

        pstmt.setObject(SQL_IDX_RECORD_MODIFIED, record.getLong(Record.REC_MODIFIED.getName()));
        record.remove(Record.REC_MODIFIED.getName());
        pstmt.setObject(SQL_IDX_FILE_MODIFIED, record.getLong(Record.FILE_MODIFIED.getName()));
        record.remove(Record.FILE_MODIFIED.getName());
        pstmt.setObject(SQL_IDX_FILE_SIZE, record.getLong(Record.FILE_SIZE.getName()));
        record.remove(Record.FILE_SIZE.getName());

        pstmt.setBoolean(SQL_IDX_HAS_SIGNATURE, record.getBoolean(Record.HAS_SIGNATURE.getName()));
        record.remove(Record.HAS_SIGNATURE.getName());
        pstmt.setBoolean(SQL_IDX_HAS_SOURCES, record.getBoolean(Record.HAS_SOURCES.getName()));
        record.remove(Record.HAS_SOURCES.getName());
        pstmt.setBoolean(SQL_IDX_HAS_JAVADOC, record.getBoolean(Record.HAS_JAVADOC.getName()));
        record.remove(Record.HAS_JAVADOC.getName());

        // Do not store SHA1 field if it exceeds maximum length of 40
        String sha1Value = record.getString(Record.SHA1.getName());
        if (sha1Value == null) {
            pstmt.setString(SQL_IDX_SHA1, null);
        } else if (sha1Value.length() <= SHA1_MAX_LENGTH) {
            pstmt.setString(SQL_IDX_SHA1, sha1Value);
            record.remove(Record.SHA1.getName());
        } else {
            pstmt.setString(SQL_IDX_SHA1, null);
            LOG.warning("SHA1 value is null or exceeds 40 characters and will be set to null for record " + record);
        }

        pstmt.setString(SQL_IDX_GROUP_ID, record.getString(Record.GROUP_ID.getName()));
        record.remove(Record.GROUP_ID.getName());
        String artifactId = record.getString(Record.ARTIFACT_ID.getName());
        pstmt.setString(SQL_IDX_ARTIFACT_ID, artifactId);
        record.remove(Record.ARTIFACT_ID.getName());
        String artifactVersion = record.getString(Record.VERSION.getName());
        pstmt.setString(SQL_IDX_ARTIFACT_VERSION, artifactVersion);
        record.remove(Record.VERSION.getName());

        String classifier = record.getString(Record.CLASSIFIER.getName());
        pstmt.setString(SQL_IDX_CLASSIFIER, classifier);
        record.remove(Record.CLASSIFIER.getName());
        pstmt.setString(SQL_IDX_PACKAGING, record.getString(Record.PACKAGING.getName()));
        record.remove(Record.PACKAGING.getName());
        String fileExtension = record.getString(Record.FILE_EXTENSION.getName());
        pstmt.setString(SQL_IDX_FILE_EXTENSION, fileExtension);
        record.remove(Record.FILE_EXTENSION.getName());

        pstmt.setString(SQL_IDX_FILE_NAME, record.getString(JSON_FIELD_FILE_NAME));
        record.remove(JSON_FIELD_FILE_NAME);

        pstmt.setString(SQL_IDX_NAME, record.getString(Record.NAME.getName()));
        record.remove(Record.NAME.getName());
        pstmt.setString(SQL_IDX_DESCRIPTION, record.getString(Record.DESCRIPTION.getName()));
        record.remove(Record.DESCRIPTION.getName());

        pstmt.setString(SQL_IDX_BUNDLE_DESCRIPTION, record.getString(Record.OSGI_BUNDLE_DESCRIPTION.getName()));
        record.remove(Record.OSGI_BUNDLE_DESCRIPTION.getName());
        pstmt.setString(SQL_IDX_BUNDLE_DOCURL, record.getString(Record.OSGI_EXPORT_DOCURL.getName()));
        record.remove(Record.OSGI_EXPORT_DOCURL.getName());
        pstmt.setString(SQL_IDX_BUNDLE_LICENSE, record.getString(Record.OSGI_BUNDLE_LICENSE.getName()));
        record.remove(Record.OSGI_BUNDLE_LICENSE.getName());
        pstmt.setString(SQL_IDX_BUNDLE_NAME, record.getString(Record.OSGI_BUNDLE_NAME.getName()));
        record.remove(Record.OSGI_BUNDLE_NAME.getName());
        pstmt.setString(SQL_IDX_BUNDLE_SYMBOLICNAME, record.getString(Record.OSGI_BUNDLE_SYMBOLIC_NAME.getName()));
        record.remove(Record.OSGI_BUNDLE_SYMBOLIC_NAME.getName());
        pstmt.setString(SQL_IDX_BUNDLE_VERSION, record.getString(Record.OSGI_BUNDLE_VERSION.getName()));
        record.remove(Record.OSGI_BUNDLE_VERSION.getName());
        pstmt.setString(SQL_IDX_EXPORT_PACKAGE, record.getString(Record.OSGI_EXPORT_PACKAGE.getName()));
        record.remove(Record.OSGI_EXPORT_PACKAGE.getName());
        pstmt.setString(SQL_IDX_IMPORT_PACKAGE, record.getString(Record.OSGI_IMPORT_PACKAGE.getName()));
        record.remove(Record.OSGI_IMPORT_PACKAGE.getName());
        pstmt.setString(SQL_IDX_REQUIRE_BUNDLE, record.getString(Record.OSGI_REQUIRE_BUNDLE.getName()));
        record.remove(Record.OSGI_REQUIRE_BUNDLE.getName());
        pstmt.setString(SQL_IDX_EXPORT_SERVICE, record.getString(Record.OSGI_EXPORT_SERVICE.getName()));
        record.remove(Record.OSGI_EXPORT_SERVICE.getName());

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
                Indexes.ascending(Record.GROUP_ID.getName()),
                Indexes.ascending(Record.ARTIFACT_ID.getName()),
                Indexes.ascending(Record.VERSION.getName()),
                Indexes.ascending(VersionAnalyser.KEY_VERSION_SEQ),
                Indexes.ascending(VersionAnalyser.KEY_MAJOR_VERSION)
            ));
        }
        LOG.log(Level.INFO, "MongoDB createIndex finished, execution time {0} ms", new Object[]{System.currentTimeMillis() - start});
    }
}
