package org.mavendb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.persistence.config.PersistenceUnitProperties;

/**
 * Entrance of the application.
 *
 * @author amosshi
 */
public class Main {

    /**
     * Logger.
     */
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    /**
     * Configuration file name: <code>config.properties</code>.
     */
    private static final String CONFIG_FILE = "config.properties";

    /**
     * SQL script to create schema.
     */
    static final String DB_CREATE_SQL = "create.sql";

    /**
     * SQL script to refresh data.
     */
    static final String DB_DATA_REFRESH_SQL = "data-refresh.sql";

    /**
     * Directory for DB scripts.
     */
    static final String DIR_DB = "db";

    /**
     * Directory for Configuration files.
     */
    private static final String DIR_ETC = "etc";

    /**
     * Directory for Big cache files.
     */
    static final String DIR_VAR = "var";

    /**
     * Get the directory which contains the configuration or scripts.
     *
     * @param dir Directory name, like {@link #DIR_DB}, {@link #DIR_ETC}, {@link #DIR_VAR}
     * @param file Add File name to result, if it is not null / not empty
     */
    static String getDirectoryFileName(String dir, String file) {
        File baseDir = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        String result = baseDir.getParent() + File.separator + dir;
        if (StringUtils.isNotBlank(file)) {
            result = result + File.separator + file;
        }
        return result;
    }

    /**
     * Load the {@link #CONFIG_FILE} or Docker <code>ENV</code>.
     */
    private static Properties loadConfig() throws IOException {

        // Set Properties
        Properties config = new Properties();

        // Get the config file name
        String configFileName = Main.getDirectoryFileName(DIR_ETC, CONFIG_FILE);

        // Load the Config values
        Properties configValues = new Properties();
        try (BufferedReader br = new BufferedReader(new FileReader(configFileName, StandardCharsets.UTF_8))) {
            configValues.load(br);
        }

        String jdbcUrl = configValues.getProperty(PersistenceUnitProperties.JDBC_URL);
        if (StringUtils.isNotBlank(System.getenv(DockerEnv.MAVENDB_MYSQL_HOST.name()))) {
            jdbcUrl = jdbcUrl.replace("127.0.0.1", System.getenv(DockerEnv.MAVENDB_MYSQL_HOST.name()));
        }
        if (StringUtils.isNotBlank(System.getenv(DockerEnv.MAVENDB_MYSQL_PORT.name()))) {
            jdbcUrl = jdbcUrl.replace("3306", System.getenv(DockerEnv.MAVENDB_MYSQL_PORT.name()));
        }

        // Set JDBC URL
        config.setProperty(PersistenceUnitProperties.JDBC_URL, jdbcUrl);

        // Set JDBC User
        config.setProperty(PersistenceUnitProperties.JDBC_USER, StringUtils.isBlank(System.getenv(DockerEnv.MAVENDB_MYSQL_USER.name()))
                ? configValues.getProperty(PersistenceUnitProperties.JDBC_USER)
                : System.getenv(DockerEnv.MAVENDB_MYSQL_USER.name()));

        // Set JDBC Pass
        config.setProperty(PersistenceUnitProperties.JDBC_PASSWORD, StringUtils.isBlank(System.getenv(DockerEnv.MAVENDB_MYSQL_PASS.name()))
                ? configValues.getProperty(PersistenceUnitProperties.JDBC_PASSWORD)
                : System.getenv(DockerEnv.MAVENDB_MYSQL_PASS.name()));

        return config;
    }

    /**
     * Entrance of the application.
     *
     * @param args the command line arguments
     * @throws IOException Exception
     */
    public static void main(String[] args) throws IOException {

        // Log formatter.
        // @see https://stackoverflow.com/questions/194765/how-do-i-get-java-logging-output-to-appear-on-a-single-line
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n");
        LOG.log(Level.INFO, "Started");

        CommandLine line;
        try {
            // parse the command line arguments
            line = new DefaultParser().parse(CommandOptions.OPTIONS, args);
        } catch (ParseException exp) {
            // oops, something went wrong
            LOG.log(Level.SEVERE, "Comand line paramter parsing failed.", exp);
            Main.printHelp();
            return;
        }

        if (line.hasOption(CommandOptions.OPTION_REPOS_FOLDER_LONGOPT)) {
            String reposFolder = line.getOptionValue(CommandOptions.OPTION_REPOS_FOLDER_LONGOPT);
            new MvnScanner(URI.create(reposFolder)).perform(loadConfig());
        } else {
            Main.printHelp();
        }

        LOG.log(Level.INFO, "Finished");

    }

    /**
     * Print out help information to command line.
     */
    @SuppressWarnings("java:S106") // Standard outputs should not be used directly to log anything -- Help info need come to System.out
    private static void printHelp() {
        String jarFilename = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getName();
        new HelpFormatter().printHelp(String.format("java -jar %s [args...]", jarFilename), CommandOptions.OPTIONS);
    }

    /**
     * Command line options.
     */
    static final class CommandOptions {

        /**
         * Command line options.
         */
        private static final Options OPTIONS = new Options();
        /**
         * Command line option: Maven Repos name long name format.
         */
        private static final String OPTION_REPOS_FOLDER_LONGOPT = "reposfolder";
        /**
         * Command line option: Maven Repos name to scan, like central, spring.
         */
        @SuppressWarnings(value="UUF_UNUSED_FIELD")
        private static final Option OPTION_RESPOSNAME = new Option("r", OPTION_REPOS_FOLDER_LONGOPT, true, "Maven Repos folder to scan, like central, spring; the folder will match to the config file at etc/repos-<the name>.properties. Example values: central, spring");
        /**
         * Command line option: print help information.
         */
        private static final Option OPTION_HELP = new Option("h", "help", false, "Printout help information");

        private CommandOptions() {
        }

        static {
            OPTIONS.addOption(OPTION_RESPOSNAME);
            OPTIONS.addOption(OPTION_HELP);
        }
    }

    /**
     * Docker environment variables.
     */
    enum DockerEnv {
        MAVENDB_MYSQL_HOST,
        MAVENDB_MYSQL_PORT,
        MAVENDB_MYSQL_USER,
        MAVENDB_MYSQL_PASS;
    }
}
