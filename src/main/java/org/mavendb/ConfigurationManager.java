package org.mavendb;

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
    private static final String CONFIG_MYSQL_BATCH_SIZE = "org.mavendb.mysql.batch.size";
    private static final String CONFIG_PSQL_URL = "org.mavendb.psql.url";
    private static final String CONFIG_PSQL_USER = "org.mavendb.psql.user";
    private static final String CONFIG_PSQL_PASSWORD = "org.mavendb.psql.password";
    private static final String CONFIG_PSQL_BATCH_SIZE = "org.mavendb.psql.batch.size";
    private static final String CONFIG_MONGODB_URL = "org.mavendb.mongodb.url";
    private static final String CONFIG_MONGODB_BATCH_SIZE = "org.mavendb.mongodb.batch.size";
    private static final String CONFIG_THREAD_POOL_SIZE = "org.mavendb.thread.pool.size";

    /* ------- Configuration Defaults ------- */
    protected static final String DATABASE_NAME = "mavendb";
    protected static final String DEFAULT_MYSQL_URL = "jdbc:mysql://localhost:3306/" + DATABASE_NAME;
    protected static final String DEFAULT_PSQL_URL = "jdbc:postgresql://localhost:5432/" + DATABASE_NAME;
    private static final int DEFAULT_MYSQL_BATCH_SIZE = 20000;
    private static final int DEFAULT_PSQL_BATCH_SIZE = 20000;
    private static final int DEFAULT_MONGODB_BATCH_SIZE = 20000;
    private static final int MIN_BATCH_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 100000;
    private static final int MIN_THREAD_POOL_SIZE = 2;

    private final Properties config;

    protected final Properties mysqlConnectionProps = new Properties();
    protected final Properties psqlConnectionProps = new Properties();

    private final int cacheMysqlBatchSize;
    private final int cachePsqlBatchSize;
    private final int cacheMongodbBatchSize;


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
     * Parse and validate batch size from configuration with fallback to default.
     *
     * @param configKey Configuration property key
     * @param defaultValue Default batch size if not configured
     * @return Validated batch size
     */
    private int parseBatchSize(String configKey, int defaultValue) {
        String batchSizeStr = config.getProperty(configKey);
        if (batchSizeStr == null || batchSizeStr.isBlank()) {
            LOG.log(Level.INFO, "{0} batch size not configured, using default: {1}",
                    new Object[]{configKey, defaultValue});
            return defaultValue;
        }

        try {
            int batchSize = Integer.parseInt(batchSizeStr.trim());
            if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
                LOG.log(Level.WARNING,
                        "{0} batch size {1} is out of valid range [{2}, {3}], using default: {4}",
                        new Object[]{configKey, batchSize, MIN_BATCH_SIZE, MAX_BATCH_SIZE, defaultValue});
                return defaultValue;
            }
            LOG.log(Level.INFO, "{0} batch size configured: {1}", new Object[]{configKey, batchSize});
            return batchSize;
        } catch (NumberFormatException e) {
            LOG.log(Level.WARNING, "{0} batch size configuration invalid: {1}, using default: {2}",
                    new Object[]{configKey, batchSizeStr, defaultValue});
            return defaultValue;
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
        mysqlConnectionProps.setProperty("useCompression", "true");
        mysqlConnectionProps.setProperty("useLocalSessionState", "true");
        mysqlConnectionProps.setProperty("useServerPrepStmts", "true");
        mysqlConnectionProps.setProperty("useSSL", "false");
        mysqlConnectionProps.setProperty("zeroDateTimeBehavior", "CONVERT_TO_NULL");

        mysqlConnectionProps.setProperty("user", this.getDatabaseUser(CONFIG_MYSQL_USER));
        mysqlConnectionProps.setProperty("password", this.getDatabasePassword(CONFIG_MYSQL_PASSWORD));

        // Initialize PSQL connection properties with defaults
        psqlConnectionProps.setProperty("ssl", "false");
        psqlConnectionProps.setProperty("user", this.getDatabaseUser(CONFIG_PSQL_USER));
        psqlConnectionProps.setProperty("password", this.getDatabasePassword(CONFIG_PSQL_PASSWORD));

        // Initialize batch sizes
        this.cacheMysqlBatchSize = parseBatchSize(CONFIG_MYSQL_BATCH_SIZE, DEFAULT_MYSQL_BATCH_SIZE);
        this.cachePsqlBatchSize = parseBatchSize(CONFIG_PSQL_BATCH_SIZE, DEFAULT_PSQL_BATCH_SIZE);
        this.cacheMongodbBatchSize = parseBatchSize(CONFIG_MONGODB_BATCH_SIZE, DEFAULT_MONGODB_BATCH_SIZE);
   }


    /**
     * Parse and validate thread pool size from configuration.
     *
     * @return Validated thread pool size
     */
    public int parseThreadPoolSize() {
        String threadPoolStr = config.getProperty(CONFIG_THREAD_POOL_SIZE);
        if (threadPoolStr == null || threadPoolStr.isBlank()) {
            int defaultPoolSize = Runtime.getRuntime().availableProcessors();
            defaultPoolSize = Math.max(MIN_THREAD_POOL_SIZE, defaultPoolSize);
            LOG.log(Level.INFO,
                    "Thread pool size not configured, using default based on available processors: {0}",
                    defaultPoolSize);
            return defaultPoolSize;
        }

        try {
            int poolSize = Integer.parseInt(threadPoolStr.trim());
            poolSize = Math.max(MIN_THREAD_POOL_SIZE, poolSize);
            LOG.log(Level.INFO, "Thread pool size configured: {0}", poolSize);
            return poolSize;
        } catch (NumberFormatException e) {
            int defaultPoolSize = Math.max(MIN_THREAD_POOL_SIZE, Runtime.getRuntime().availableProcessors());
            LOG.log(Level.WARNING, "Thread pool size configuration invalid: {0}, using default: {1}",
                    new Object[]{threadPoolStr, defaultPoolSize});
            return defaultPoolSize;
        }
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
        return this.cacheMysqlBatchSize;
    }

    /**
     * Get PostgreSQL batch size from configuration.
     *
     * @return PostgreSQL batch size
     */
    public int getPsqlBatchSize() {
        return this.cachePsqlBatchSize;
    }

    /**
     * Get MongoDB batch size from configuration.
     *
     * @return MongoDB batch size
     */
    public int getMongodbBatchSize() {
        return this.cacheMongodbBatchSize;
    }
}
