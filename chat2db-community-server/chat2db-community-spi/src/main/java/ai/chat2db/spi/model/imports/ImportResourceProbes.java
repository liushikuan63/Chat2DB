package ai.chat2db.spi.model.imports;

import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared read-only catalog helpers for dialect-owned {@code probeImportResources} implementations.
 *
 * <p>Every helper answers "unknown" by throwing, never by inventing a value: a dialect manager
 * catches the failure and records it in {@link ImportResourceSnapshot#evidence()}, so import
 * admission degrades instead of trusting a fabricated number.
 */
public final class ImportResourceProbes {

    private ImportResourceProbes() {
    }

    /** Reads one integer column from a probe query; throws when the query returns no row. */
    public static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new SQLException("Resource probe returned no rows");
            }
            return rows.getInt(1);
        }
    }

    /** Reads one boolean column from a probe query; throws when the query returns no row. */
    public static boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new SQLException("Resource probe returned no rows");
            }
            return rows.getBoolean(1);
        }
    }

    /**
     * Reads one nullable long column from a probe query; {@code null} means SQL NULL, while a query
     * that returns no row at all throws.
     */
    public static Long queryNullableLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new SQLException("Resource probe returned no rows");
            }
            long value = rows.getLong(1);
            return rows.wasNull() ? null : value;
        }
    }

    /**
     * Counts rows of a query that must return exactly one count column. Used for trigger counts,
     * where an empty catalog is a legitimate zero rather than an unknown.
     */
    public static int queryCount(Connection connection, String sql) throws SQLException {
        return queryInt(connection, sql);
    }

    /** Reads one string column from a probe query; throws when the query returns no row. */
    public static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new SQLException("Resource probe returned no rows");
            }
            return rows.getString(1);
        }
    }

    /** Trims to null so a blank schema argument reaches SQL as NULL and hits the dialect default. */
    public static String blankToNull(String value) {
        return StringUtils.trimToNull(value);
    }
}
