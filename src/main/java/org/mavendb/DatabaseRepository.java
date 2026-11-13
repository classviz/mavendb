package org.mavendb;

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
import java.util.concurrent.ConcurrentLinkedQueue;
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
import org.postgresql.util.PGobject;

/**
 * Repository for database operations with support for MySQL, PostgreSQL, and MongoDB.
 * Handles all persistent storage operations for Maven records and documents.
 *
 * <p>Connections are reused across batches: SQL writers borrow an idle connection (or open a new one)
 * and return it after commit, so at most one connection exists per concurrent writer.
 * MongoDB uses one shared, thread-safe {@link MongoClient}.
 */
class DatabaseRepository implements AutoCloseable {

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

    /** Timeout for checking an idle connection is still alive before reuse. */
    private static final int CONNECTION_VALID_TIMEOUT_SECONDS = 2;

    private final DatabaseType dbType;
    private final String dbUrl;
    private final Properties sqlConnectionProps;

    /** Idle SQL connections ready for reuse by writer threads. */
    private final ConcurrentLinkedQueue<Connection> idleConnections = new ConcurrentLinkedQueue<>();

    /** Shared MongoDB client, created lazily; it is thread-safe and pools connections internally. */
    private MongoClient mongoClient;

    /**
     * Constructor for SQL-based repositories (MySQL, PostgreSQL, SQLite).
     *
     * @param dbType Database type (MYSQL, PSQL or SQLITE)
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
                    AppPaths.getDbScriptFileName(this.dbType, script),
                    StandardCharsets.UTF_8)
            ) {
                long start = System.currentTimeMillis();
                LOG.log(Level.INFO, "SQL {0} execution started", script);
                conn.setAutoCommit(false);
                ScriptRunner runner = new ScriptRunner(conn);
                // Fail the import on any script error; the default only prints it to stderr and carries on
                runner.setStopOnError(true);
                runner.runScript(r);
                LOG.log(Level.INFO, "SQL {0} execution finished, execution time {1} ms", new Object[]{script, System.currentTimeMillis() - start});
            }
        } else {
            throw new UnsupportedOperationException("SQL script execution is not supported for database type: " + this.dbType);
        }
    }

    /**
     * Store SQL records (MySQL, PostgreSQL, SQLite) to database.
     *
     * @param rows Prepared rows to persist, see {@link #toSQLRow(Document)}
     * @param counter Record counter for logging
     * @throws IllegalStateException if the batch could not be written; the transaction is rolled back
     */
    @SuppressFBWarnings(value = "VA_FORMAT_STRING_USES_NEWLINE", justification = "False positive for Java text blocks")
    protected void storeSQL(List<SqlRow> rows, final long counter) {
        Connection conn = null;
        try {
            conn = borrowConnection();
            LocalDateTime begin = LocalDateTime.now();

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
                for (SqlRow row : rows) {
                    row.bindTo(pstmt);
                    pstmt.addBatch();
                }
                // Execute remaining batch
                pstmt.executeBatch();
                conn.commit();
            }

            // Success: hand the connection back for the next batch
            this.idleConnections.offer(conn);
            conn = null;

            Duration duration = Duration.between(begin, LocalDateTime.now());
            LOG.log(Level.INFO, "persist finished for records counter={0} in seconds={1}, batchSize={2}",
                    new Object[]{counter, duration.toSeconds(), rows.size()});
        } catch (SQLException e) {
            // Fail the run instead of silently losing the batch
            throw new IllegalStateException("SQL persist failed for records counter=" + counter, e);
        } finally {
            // Failure: discard the connection, its state is unknown
            if (conn != null) {
                closeQuietly(conn, true);
            }
        }
    }

    /**
     * Take an idle connection, or open a new one when none is available.
     */
    private Connection borrowConnection() throws SQLException {
        Connection conn;
        while ((conn = this.idleConnections.poll()) != null) {
            if (conn.isValid(CONNECTION_VALID_TIMEOUT_SECONDS)) {
                return conn;
            }
            LOG.log(Level.WARNING, "Discarding invalid idle {0} writer connection", this.dbType);
            closeQuietly(conn, false);
        }
        conn = DriverManager.getConnection(this.dbUrl, this.sqlConnectionProps);
        conn.setAutoCommit(false);
        LOG.log(Level.INFO, "Opened new {0} writer connection", this.dbType);
        return conn;
    }

    private static void closeQuietly(Connection conn, boolean rollback) {
        try {
            if (rollback) {
                conn.rollback();
            }
        } catch (SQLException e) {
            LOG.log(Level.FINE, "Rollback failed while discarding connection", e);
        }
        try {
            conn.close();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Failed to close SQL connection", e);
        }
    }

    /**
     * Close all idle SQL writer connections. Call after all writers finished,
     * so no idle connection is left open while the post-load scripts run.
     */
    protected void closeSQLConnections() {
        Connection conn;
        int count = 0;
        while ((conn = this.idleConnections.poll()) != null) {
            closeQuietly(conn, false);
            count++;
        }
        if (count > 0) {
            LOG.log(Level.INFO, "Closed {0} {1} writer connections", new Object[]{count, this.dbType});
        }
    }

    private synchronized MongoClient getMongoClient() {
        if (this.mongoClient == null) {
            this.mongoClient = MongoClients.create(this.dbUrl);
        }
        return this.mongoClient;
    }

    @Override
    public synchronized void close() {
        closeSQLConnections();
        if (this.mongoClient != null) {
            this.mongoClient.close();
            this.mongoClient = null;
        }
    }

    /**
     * Prepare the gav row parameters of one record, with database-specific handling.
     *
     * <p>Thread safe: called on the conversion threads, so this work stays off the writer.
     * Removes the bound fields from the record; what remains is stored in the json column.
     *
     * @param record Maven record to convert
     * @return Row parameters, bound later by {@link #storeSQL(List, long)}
     * @throws SQLException if a parameter value cannot be created
     */
    protected SqlRow toSQLRow(Document record) throws SQLException {
        SqlRow row = new SqlRow();
        row.setLong(SQL_IDX_SEQID, record.getLong(JSON_FIELD_ID));

        row.setInt(SQL_IDX_MAJOR_VERSION, record.getInteger(VersionAnalyser.KEY_MAJOR_VERSION));
        record.remove(VersionAnalyser.KEY_MAJOR_VERSION);
        row.setLong(SQL_IDX_VERSION_SEQ, record.getLong(VersionAnalyser.KEY_VERSION_SEQ));
        record.remove(VersionAnalyser.KEY_VERSION_SEQ);

        row.setObject(SQL_IDX_RECORD_MODIFIED, record.getLong(Record.REC_MODIFIED.getName()));
        record.remove(Record.REC_MODIFIED.getName());
        row.setObject(SQL_IDX_FILE_MODIFIED, record.getLong(Record.FILE_MODIFIED.getName()));
        record.remove(Record.FILE_MODIFIED.getName());
        row.setObject(SQL_IDX_FILE_SIZE, record.getLong(Record.FILE_SIZE.getName()));
        record.remove(Record.FILE_SIZE.getName());

        row.setBoolean(SQL_IDX_HAS_SIGNATURE, record.getBoolean(Record.HAS_SIGNATURE.getName()));
        record.remove(Record.HAS_SIGNATURE.getName());
        row.setBoolean(SQL_IDX_HAS_SOURCES, record.getBoolean(Record.HAS_SOURCES.getName()));
        record.remove(Record.HAS_SOURCES.getName());
        row.setBoolean(SQL_IDX_HAS_JAVADOC, record.getBoolean(Record.HAS_JAVADOC.getName()));
        record.remove(Record.HAS_JAVADOC.getName());

        // Do not store SHA1 field if it exceeds maximum length of 40
        String sha1Value = record.getString(Record.SHA1.getName());
        if (sha1Value == null) {
            row.setString(SQL_IDX_SHA1, null);
        } else if (sha1Value.length() <= SHA1_MAX_LENGTH) {
            row.setString(SQL_IDX_SHA1, sha1Value);
            record.remove(Record.SHA1.getName());
        } else {
            row.setString(SQL_IDX_SHA1, null);
            // Publisher uploaded a malformed .sha1 file (e.g. a path with no hash); the raw value stays in the json column
            LOG.log(Level.INFO, "Malformed SHA1 stored as null for {0}:{1}:{2}: {3}", new Object[]{
                record.getString(Record.GROUP_ID.getName()), record.getString(Record.ARTIFACT_ID.getName()),
                record.getString(Record.VERSION.getName()), sha1Value});
        }

        row.setString(SQL_IDX_GROUP_ID, record.getString(Record.GROUP_ID.getName()));
        record.remove(Record.GROUP_ID.getName());
        String artifactId = record.getString(Record.ARTIFACT_ID.getName());
        row.setString(SQL_IDX_ARTIFACT_ID, artifactId);
        record.remove(Record.ARTIFACT_ID.getName());
        String artifactVersion = record.getString(Record.VERSION.getName());
        row.setString(SQL_IDX_ARTIFACT_VERSION, artifactVersion);
        record.remove(Record.VERSION.getName());

        String classifier = record.getString(Record.CLASSIFIER.getName());
        row.setString(SQL_IDX_CLASSIFIER, classifier);
        record.remove(Record.CLASSIFIER.getName());
        row.setString(SQL_IDX_PACKAGING, record.getString(Record.PACKAGING.getName()));
        record.remove(Record.PACKAGING.getName());
        String fileExtension = record.getString(Record.FILE_EXTENSION.getName());
        row.setString(SQL_IDX_FILE_EXTENSION, fileExtension);
        record.remove(Record.FILE_EXTENSION.getName());

        row.setString(SQL_IDX_FILE_NAME, record.getString(JSON_FIELD_FILE_NAME));
        record.remove(JSON_FIELD_FILE_NAME);

        row.setString(SQL_IDX_NAME, record.getString(Record.NAME.getName()));
        record.remove(Record.NAME.getName());
        row.setString(SQL_IDX_DESCRIPTION, record.getString(Record.DESCRIPTION.getName()));
        record.remove(Record.DESCRIPTION.getName());

        row.setString(SQL_IDX_BUNDLE_DESCRIPTION, record.getString(Record.OSGI_BUNDLE_DESCRIPTION.getName()));
        record.remove(Record.OSGI_BUNDLE_DESCRIPTION.getName());
        row.setString(SQL_IDX_BUNDLE_DOCURL, record.getString(Record.OSGI_EXPORT_DOCURL.getName()));
        record.remove(Record.OSGI_EXPORT_DOCURL.getName());
        row.setString(SQL_IDX_BUNDLE_LICENSE, record.getString(Record.OSGI_BUNDLE_LICENSE.getName()));
        record.remove(Record.OSGI_BUNDLE_LICENSE.getName());
        row.setString(SQL_IDX_BUNDLE_NAME, record.getString(Record.OSGI_BUNDLE_NAME.getName()));
        record.remove(Record.OSGI_BUNDLE_NAME.getName());
        row.setString(SQL_IDX_BUNDLE_SYMBOLICNAME, record.getString(Record.OSGI_BUNDLE_SYMBOLIC_NAME.getName()));
        record.remove(Record.OSGI_BUNDLE_SYMBOLIC_NAME.getName());
        row.setString(SQL_IDX_BUNDLE_VERSION, record.getString(Record.OSGI_BUNDLE_VERSION.getName()));
        record.remove(Record.OSGI_BUNDLE_VERSION.getName());
        row.setString(SQL_IDX_EXPORT_PACKAGE, record.getString(Record.OSGI_EXPORT_PACKAGE.getName()));
        record.remove(Record.OSGI_EXPORT_PACKAGE.getName());
        row.setString(SQL_IDX_IMPORT_PACKAGE, record.getString(Record.OSGI_IMPORT_PACKAGE.getName()));
        record.remove(Record.OSGI_IMPORT_PACKAGE.getName());
        row.setString(SQL_IDX_REQUIRE_BUNDLE, record.getString(Record.OSGI_REQUIRE_BUNDLE.getName()));
        record.remove(Record.OSGI_REQUIRE_BUNDLE.getName());
        row.setString(SQL_IDX_EXPORT_SERVICE, record.getString(Record.OSGI_EXPORT_SERVICE.getName()));
        record.remove(Record.OSGI_EXPORT_SERVICE.getName());

        // Remove _id from json if it's the only field left
        if (record.size() == 1) {
            record.remove(JSON_FIELD_ID);
        }

        // Handle JSON field with database-specific types
        String json = record.isEmpty() ? null : record.toJson();
        switch (this.dbType) {
            // MySQL JSON column, SQLite stores JSON as TEXT
            case MYSQL, SQLITE -> row.setString(SQL_IDX_JSON, json);
            case PSQL -> {
                if (json == null) {
                    row.setObject(SQL_IDX_JSON, null, java.sql.Types.OTHER);
                } else {
                    PGobject jsonObject = new PGobject();
                    jsonObject.setType("jsonb");
                    jsonObject.setValue(json);
                    row.setObject(SQL_IDX_JSON, jsonObject);
                }
            }
            case MONGODB -> throw new IllegalStateException("SQL parameter binding is not used for MongoDB");
        }
        return row;
    }

    /**
     * Store MongoDB documents to database.
     *
     * @param storeDocuments Documents to persist
     * @param counter Record counter for logging
     */
    protected void storeMongoDB(List<Document> storeDocuments, final long counter, final String mongoIndexId) {
        LocalDateTime begin = LocalDateTime.now();
        getMongoClient().getDatabase(ConfigurationManager.DATABASE_NAME).getCollection(mongoIndexId).insertMany(
            storeDocuments,
            new InsertManyOptions().ordered(false)
        );
        Duration duration = Duration.between(begin, LocalDateTime.now());
        LOG.log(Level.INFO, "MongoDB persist finished for position={0} in seconds={1} Millis={2}, batchSize={3}",
                new Object[]{counter, duration.toSeconds(), duration.toMillis(), storeDocuments.size()});
    }

    protected void createIndexesMongoDB(String indexId) {
        long start = System.currentTimeMillis();
        getMongoClient().getDatabase(ConfigurationManager.DATABASE_NAME).getCollection(indexId).createIndex(Indexes.compoundIndex(
            Indexes.ascending(Record.GROUP_ID.getName()),
            Indexes.ascending(Record.ARTIFACT_ID.getName()),
            Indexes.ascending(Record.VERSION.getName()),
            Indexes.ascending(VersionAnalyser.KEY_VERSION_SEQ),
            Indexes.ascending(VersionAnalyser.KEY_MAJOR_VERSION)
        ));
        LOG.log(Level.INFO, "MongoDB createIndex finished, execution time {0} ms", new Object[]{System.currentTimeMillis() - start});
    }

    /**
     * Parameter values of one gav row: the JDBC setter calls are recorded on a conversion thread,
     * and replayed on the writer's {@link PreparedStatement} with the same setter and value types.
     */
    static final class SqlRow {
        private static final byte STRING = 0;
        private static final byte INT = 1;
        private static final byte LONG = 2;
        private static final byte BOOLEAN = 3;
        private static final byte OBJECT = 4;
        private static final byte OBJECT_TYPED = 5;

        private final Object[] values = new Object[SQL_IDX_JSON];
        private final byte[] kinds = new byte[SQL_IDX_JSON];
        private final int[] sqlTypes = new int[SQL_IDX_JSON];

        private void put(int parameterIndex, byte kind, Object value) {
            this.kinds[parameterIndex - 1] = kind;
            this.values[parameterIndex - 1] = value;
        }

        void setString(int parameterIndex, String value) {
            put(parameterIndex, STRING, value);
        }

        void setInt(int parameterIndex, int value) {
            put(parameterIndex, INT, value);
        }

        void setLong(int parameterIndex, long value) {
            put(parameterIndex, LONG, value);
        }

        void setBoolean(int parameterIndex, boolean value) {
            put(parameterIndex, BOOLEAN, value);
        }

        void setObject(int parameterIndex, Object value) {
            put(parameterIndex, OBJECT, value);
        }

        void setObject(int parameterIndex, Object value, int sqlType) {
            put(parameterIndex, OBJECT_TYPED, value);
            this.sqlTypes[parameterIndex - 1] = sqlType;
        }

        /**
         * Bind all parameters to the statement.
         */
        void bindTo(PreparedStatement pstmt) throws SQLException {
            for (int i = 0; i < SQL_IDX_JSON; i++) {
                Object value = this.values[i];
                switch (this.kinds[i]) {
                    case STRING -> pstmt.setString(i + 1, (String) value);
                    case INT -> pstmt.setInt(i + 1, (Integer) value);
                    case LONG -> pstmt.setLong(i + 1, (Long) value);
                    case BOOLEAN -> pstmt.setBoolean(i + 1, (Boolean) value);
                    case OBJECT -> pstmt.setObject(i + 1, value);
                    case OBJECT_TYPED -> pstmt.setObject(i + 1, value, this.sqlTypes[i]);
                    default -> throw new IllegalStateException("Unknown parameter kind: " + this.kinds[i]);
                }
            }
        }
    }
}
