package org.mavendb;

import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages application configuration parsing and validation.
 * Handles batch sizes, thread pool configuration, and database credentials.
 */
class ConfigurationManager {

    /** Logger. */
    private static final Logger LOG = Logger.getLogger(ConfigurationManager.class.getName());

    /* ------- Configuration Property Keys ------- */
    private static final String CONFIG_MYSQL_URL = "org.mavendb.mysql.url";
    private static final String CONFIG_MYSQL_USER = "org.mavendb.mysql.user";
    private static final String CONFIG_MYSQL_PASSWORD = "org.mavendb.mysql.password";
    private static final String CONFIG_PSQL_URL = "org.mavendb.psql.url";
    private static final String CONFIG_PSQL_USER = "org.mavendb.psql.user";
    private static final String CONFIG_PSQL_PASSWORD = "org.mavendb.psql.password";
    private static final String CONFIG_SQLITE_URL = "org.mavendb.sqlite.url";
    private static final String CONFIG_MONGODB_URL = "org.mavendb.mongodb.url";

    /* ------- Configuration Defaults ------- */
    protected static final String DATABASE_NAME = "mavendb";
    protected static final String DEFAULT_MYSQL_URL = "jdbc:mysql://localhost:3306/" + DATABASE_NAME;
    protected static final String DEFAULT_PSQL_URL = "jdbc:postgresql://localhost:5432/" + DATABASE_NAME;
    protected static final String DEFAULT_SQLITE_URL = "jdbc:sqlite:" + DATABASE_NAME + ".sqlite";

    private static final int MIN_BATCH_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 100_000;

    /**
     * Integer settings: property key, default value, and valid range [min, max].
     * A missing, blank, non-numeric or out-of-range value falls back to the default.
     */
    enum IntSetting {
        MYSQL_BATCH_SIZE("org.mavendb.mysql.batch.size", 50_000, MIN_BATCH_SIZE, MAX_BATCH_SIZE),
        PSQL_BATCH_SIZE("org.mavendb.psql.batch.size", 50_000, MIN_BATCH_SIZE, MAX_BATCH_SIZE),
        /** For SQLite, 100k and 50k has similar performance. */
        SQLITE_BATCH_SIZE("org.mavendb.sqlite.batch.size", 100_000, MIN_BATCH_SIZE, MAX_BATCH_SIZE),
        MONGODB_BATCH_SIZE("org.mavendb.mongodb.batch.size", 20_000, MIN_BATCH_SIZE, MAX_BATCH_SIZE),
        /** Maximum concurrent writer threads; 4 gives good throughput without overwhelming the database. */
        THREAD_POOL_SIZE("org.mavendb.thread.pool.size", 4, 2, 64);

        final String key;
        final int defaultValue;
        final int min;
        final int max;

        IntSetting(String key, int defaultValue, int min, int max) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
        }
    }

    private final Properties config;

    protected final Properties mysqlConnectionProps = new Properties();
    protected final Properties psqlConnectionProps = new Properties();
    protected final Properties sqliteConnectionProps = new Properties();

    /** Parsed and validated integer settings, filled once in the constructor. */
    private final Map<IntSetting, Integer> intSettings = new EnumMap<>(IntSetting.class);


    /**
     * Get database user from configuration with logging for blank values.
     *
     * @param configKey Configuration property key
     * @return Database user name or empty string if not configured
     */
    private String getDatabaseUser(String configKey) {
        String user = config.getProperty(configKey);
        if (user == null || user.isBlank()) {
            LOG.log(Level.WARNING, "{0} user not configured, using empty string", configKey);
            return "";
        }
        return user;
    }

    /**
     * Get database password from configuration with logging for blank values.
     *
     * @param configKey Configuration property key
     * @return Database password or empty string if not configured
     */
    private String getDatabasePassword(String configKey) {
        String password = config.getProperty(configKey);
        if (password == null || password.isBlank()) {
            LOG.log(Level.WARNING, "{0} password not configured, using empty string", configKey);
            return "";
        }
        return password;
    }

    /**
     * Parse and validate an integer setting from configuration, falling back to its default.
     *
     * @param setting Setting to parse
     * @return Validated value
     */
    private int parseIntSetting(IntSetting setting) {
        String valueStr = config.getProperty(setting.key);
        if (valueStr == null || valueStr.isBlank()) {
            LOG.log(Level.INFO, "{0} not configured, using default: {1}",
                    new Object[]{setting.key, setting.defaultValue});
            return setting.defaultValue;
        }

        try {
            int value = Integer.parseInt(valueStr.trim());
            if (value < setting.min || value > setting.max) {
                LOG.log(Level.WARNING,
                        "{0} value {1} is out of valid range [{2}, {3}], using default: {4}",
                        new Object[]{setting.key, value, setting.min, setting.max, setting.defaultValue});
                return setting.defaultValue;
            }
            LOG.log(Level.INFO, "{0} configured: {1}", new Object[]{setting.key, value});
            return value;
        } catch (NumberFormatException _) {
            LOG.log(Level.WARNING, "{0} configuration invalid: {1}, using default: {2}",
                    new Object[]{setting.key, valueStr, setting.defaultValue});
            return setting.defaultValue;
        }
    }

    /**
     * Constructor.
     *
     * @param config Properties object with configuration values (will be defensively copied)
     */
    public ConfigurationManager(Properties config) {
        // Create a defensive copy to prevent external mutation of configuration
        this.config = new Properties();
        if (config != null) {
            this.config.putAll(config);
        }

        // Initialize MySQL connection properties with defaults
        mysqlConnectionProps.setProperty("allowPublicKeyRetrieval", "true");
        mysqlConnectionProps.setProperty("cachePrepStmts", "true");
        mysqlConnectionProps.setProperty("rewriteBatchedStatements", "true");
        // Server session variables for bulk load; unique_checks is not a Connector/J property, so it must go here
        mysqlConnectionProps.setProperty("sessionVariables", "unique_checks=0,foreign_key_checks=0");
        // No protocol compression: the DB is local, compression only costs CPU on both sides
        mysqlConnectionProps.setProperty("useCompression", "false");
        mysqlConnectionProps.setProperty("useLocalSessionState", "true");
        mysqlConnectionProps.setProperty("useServerPrepStmts", "false");
        mysqlConnectionProps.setProperty("useSSL", "false");
        mysqlConnectionProps.setProperty("zeroDateTimeBehavior", "CONVERT_TO_NULL");

        mysqlConnectionProps.setProperty("user", this.getDatabaseUser(CONFIG_MYSQL_USER));
        mysqlConnectionProps.setProperty("password", this.getDatabasePassword(CONFIG_MYSQL_PASSWORD));

        // Initialize PSQL connection properties with defaults
        psqlConnectionProps.setProperty("ssl", "false");
        psqlConnectionProps.setProperty("synchronous_commit", "off");  // Improve performance by disabling synchronous commit

        psqlConnectionProps.setProperty("user", this.getDatabaseUser(CONFIG_PSQL_USER));
        psqlConnectionProps.setProperty("password", this.getDatabasePassword(CONFIG_PSQL_PASSWORD));

        // Initialize SQLite connection properties with performance optimizations
        //
        // 8GB (8589934592) slowdown the write performance significantly, so we use 
        // 64MB (64000) and 640MB (64000) has the same performance
        // So we use 64MB to save memory
        sqliteConnectionProps.setProperty("cache_size", "-64000");
        // OFF mode is faster than WAL for single-writer scenarios, but can lead to database corruption on crashes, use with caution
        sqliteConnectionProps.setProperty("journal_mode", "OFF");
        // Enable memory-mapped I/O for faster reads - 1GB (1073741824) and 48GB (51539607552) has the same write performance, while create index is slower for 1GB 
        sqliteConnectionProps.setProperty("mmap_size", "1073741824");
        //
        // This is the most important setting for write performance: it controls the size of the database pages. 
        // Larger page size can significantly improve write performance for large batch inserts, but it also increases memory usage and can lead to fragmentation. 
        // 64KB is a good balance for our use case, as it provides much better performance than the default 4KB without excessive memory usage. 
        // 
        // Set page size to 64KB for optimal performance: it is faster than the default 4KB for data-refersh.sql: 479 vs 1,311 seconds as of Feb 2026
        // Note: page size must be set before database creation, so it is not configurable at runtime.
        sqliteConnectionProps.setProperty("page_size", "65536");
        // Disable synchronous mode for faster writes (use with caution as it can lead to data loss on crashes)
        sqliteConnectionProps.setProperty("synchronous", "OFF");
        // Increase temp store to memory for faster operations
        sqliteConnectionProps.setProperty("temp_store", "MEMORY");

        // Parse and validate all integer settings once
        for (IntSetting setting : IntSetting.values()) {
            this.intSettings.put(setting, parseIntSetting(setting));
        }
   }

    /**
     * Get a validated integer setting.
     *
     * @param setting Setting to get
     * @return Configured value, or its default
     */
    public int getInt(IntSetting setting) {
        return this.intSettings.get(setting);
    }

    /**
     * Get the maximum number of concurrent writer threads.
     *
     * @return Thread pool size
     */
    public int getThreadPoolSize() {
        return getInt(IntSetting.THREAD_POOL_SIZE);
    }

    /**
     * Get MySQL URL from configuration.
     *
     * @return MySQL connection URL
     */
    public String getMysqlUrl() {
        return config.getProperty(CONFIG_MYSQL_URL, DEFAULT_MYSQL_URL);
    }

    /**
     * Get PostgreSQL URL from configuration.
     *
     * @return PostgreSQL connection URL
     */
    public String getPsqlUrl() {
        return config.getProperty(CONFIG_PSQL_URL, DEFAULT_PSQL_URL);
    }

    /**
     * Get SQLite URL from configuration.
     *
     * @return SQLite connection URL
     */
    public String getSqliteUrl() {
        return config.getProperty(CONFIG_SQLITE_URL, DEFAULT_SQLITE_URL);
    }

    /**
     * Get MongoDB URL from configuration.
     *
     * @return MongoDB connection URL
     * @throws IllegalArgumentException if MongoDB URL is not configured
     */
    public String getMongodbUrl() throws IllegalArgumentException {
        String mongoUrl = config.getProperty(CONFIG_MONGODB_URL);
        if (mongoUrl == null || mongoUrl.isBlank()) {
            throw new IllegalArgumentException("MongoDB URL not configured in properties");
        }
        return mongoUrl;
    }

    /**
     * Get MySQL batch size from configuration.
     *
     * @return MySQL batch size
     */
    public int getMysqlBatchSize() {
        return getInt(IntSetting.MYSQL_BATCH_SIZE);
    }

    /**
     * Get PostgreSQL batch size from configuration.
     *
     * @return PostgreSQL batch size
     */
    public int getPsqlBatchSize() {
        return getInt(IntSetting.PSQL_BATCH_SIZE);
    }

    /**
     * Get SQLite batch size from configuration.
     *
     * @return SQLite batch size
     */
    public int getSqliteBatchSize() {
        return getInt(IntSetting.SQLITE_BATCH_SIZE);
    }

    /**
     * Get MongoDB batch size from configuration.
     *
     * @return MongoDB batch size
     */
    public int getMongodbBatchSize() {
        return getInt(IntSetting.MONGODB_BATCH_SIZE);
    }
}
