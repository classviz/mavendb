package org.mavendb;

import java.io.File;

/**
 * Locations of the application folders, resolved next to the jar file.
 *
 * <p>Depends only on {@link DatabaseType}.
 */
final class AppPaths {

    /** Directory for configuration files. */
    static final String DIR_ETC = "etc";

    /** Directory for log files. */
    static final String DIR_LOG = "log";

    /** Directory for database scripts; each database type has a sub folder, like <code>db/mysql</code>. */
    static final String DIR_DB = "db";

    private AppPaths() {
    }

    /**
     * Get the full path of a file in an application folder.
     *
     * @param dir Directory name, like {@link #DIR_ETC}, {@link #DIR_LOG}, <code>db/mysql</code>
     * @param file File name, like <code>config.properties</code>, <code>create.sql</code>
     * @return The full path of the file
     */
    static String getFileName(String dir, String file) {
        File baseDir = new File(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        return baseDir.getParent() + File.separator + dir + File.separator + file;
    }

    /**
     * Get the full path of a database script.
     *
     * @param dbType Database type, selects the <code>db/&lt;dbtype&gt;</code> folder
     * @param script Script file name, like <code>create.sql</code>
     * @return The full path of the script
     */
    static String getDbScriptFileName(DatabaseType dbType, String script) {
        return getFileName(DIR_DB + File.separator + dbType.name().toLowerCase(), script);
    }
}
