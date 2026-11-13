package org.mavendb;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Supported target database types.
 *
 * <p>Leaf type: depends on no other class in this package.
 */
enum DatabaseType {
    MYSQL,
    MONGODB,
    PSQL,
    SQLITE;

    /**
     * Lower-case names, comma separated, for help text.
     */
    static String names() {
        return Arrays.stream(DatabaseType.values()).map(e -> e.name().toLowerCase()).collect(Collectors.joining(","));
    }
}
