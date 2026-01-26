package org.mavendb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
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
    static final String DB_MYSQL_CREATE_SQL = "create.sql";

    /**
     * SQL script to refresh data.
     */
    static final String DB_MYSQL_DATA_REFRESH_SQL = "data-refresh.sql";

    /**
     * Directory for DB scripts.
     */
    static final String DIR_DB_MYSQL = "db" + File.separator + "mysql";

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
     * @param dir Directory name, like {@link #DIR_DB_MYSQL}, {@link #DIR_ETC}, {@link #DIR_VAR}
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

        // Get the config file name
        String configFileName = Main.getDirectoryFileName(DIR_ETC, CONFIG_FILE);

        // Load the Config values
        Properties configValues = new Properties();
        try (BufferedReader br = new BufferedReader(new FileReader(configFileName, StandardCharsets.UTF_8))) {
            configValues.load(br);
        }

        return configValues;
    }

    /**
     * Entrance of the application.
     *
     * @param args the command line arguments
     * @throws IOException Exception
     */
    public static void main(String[] args) throws IOException, SQLException {

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

        String dbString = line.getOptionValue(CommandOptions.OPTION_DB_TYPE_LONGOPT).toUpperCase();
        DatabaseType dbType;
        try {
            dbType = Enum.valueOf(DatabaseType.class, dbString);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.SEVERE, "Unsupported database type: " + dbString, e);
            Main.printHelp();
            return;
        }

        String reposFolder = line.getOptionValue(CommandOptions.OPTION_REPOS_FOLDER_LONGOPT);
        MvnScanner scanner;
        try {
            scanner = MvnScanner.create(reposFolder, dbType);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.SEVERE, "Invalid repository folder: " + reposFolder, e);
            return;
        }

        scanner.perform(loadConfig());

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
         * Command line option: Database type long name format.
         */
        private static final String OPTION_DB_TYPE_LONGOPT = "dbtype";

        /**
         * Command line option: Maven Repos name to scan, like central, spring.
         */
        private static final Option OPTION_DB_TYPE = Option.builder("d")
            .longOpt(OPTION_DB_TYPE_LONGOPT)
            .hasArg()
            .desc("Database type, like mysql, mongodb.")
            .required()
            .get();
        /**
         * Command line option: Maven Repos name to scan, like central, spring.
         */
        private static final Option OPTION_RESPOSNAME = Option.builder("f")
            .longOpt(OPTION_REPOS_FOLDER_LONGOPT)
            .hasArg()
            .desc("Maven Repos folder to scan, like central, spring; the folder will match to the config file at etc/repos-<the name>.properties.")
            .required()
            .get();
        /**
         * Command line option: print help information.
         */
        private static final Option OPTION_HELP = Option.builder("h")
            .longOpt("help")
            .hasArg(false)
            .desc("Printout help information")
            .get();

        private CommandOptions() {
        }

        static {
            OPTIONS.addOption(OPTION_DB_TYPE);
            OPTIONS.addOption(OPTION_RESPOSNAME);
            OPTIONS.addOption(OPTION_HELP);
        }
    }

    static enum DatabaseType {
        MYSQL,
        MONGODB
    }
}
