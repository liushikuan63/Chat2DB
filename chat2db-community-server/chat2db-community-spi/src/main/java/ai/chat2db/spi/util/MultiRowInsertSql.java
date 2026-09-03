package ai.chat2db.spi.util;

import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.druid.DbType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Merges runs of consecutive single-row {@code INSERT INTO t (c1,c2) VALUES (...)} statements into
 * multi-row {@code INSERT ... VALUES (...),(...)} statements for dialects that support the form.
 * One merged statement of a few thousand rows replaces a few thousand network round trips, which
 * is the single largest lever on bulk import throughput: plain JDBC {@link java.sql.Statement}
 * batches are still sent statement-by-statement by most drivers ({@code rewriteBatchedStatements}
 * only rewrites {@code PreparedStatement} batches), so the merge has to happen at the SQL level.
 *
 * <p>The merge is strictly an optimization with a safe fallback contract: anything unexpected —
 * a disabled switch, a dialect known to reject multi-row VALUES (the Oracle family), a statement
 * that does not parse into the plain shape, or a server that rejects the merged batch at runtime —
 * makes the caller keep or replay the exact legacy one-statement-per-row path, so an import can
 * never lose or duplicate rows because of this class.
 */
public final class MultiRowInsertSql {

    /**
     * Set {@code -Dchat2db.task.import.multiRowInsert=false} to keep the legacy
     * one-statement-per-row JDBC batch; the property is re-read per chunk so tests can toggle it.
     */
    public static final String ENABLED_PROPERTY = "chat2db.task.import.multiRowInsert";

    /** Rows per merged statement for dialects without their own statement-shape limit. */
    static final int DEFAULT_MAX_ROWS_PER_STATEMENT = 5_000;

    /** SQL Server allows at most 1000 row-value expressions per INSERT ... VALUES statement. */
    static final int SQLSERVER_MAX_ROWS_PER_STATEMENT = 1_000;

    /** Byte ceiling for one merged statement, comfortably below any max_allowed_packet default. */
    private static final int MAX_BYTES_PER_STATEMENT = 8 * 1024 * 1024;

    private static final String VALUES_KEYWORD = "VALUES";

    /** Explicit Chat2DB dialect names that accept multi-row INSERT ... VALUES. */
    private static final String[] SUPPORTED_WITH_DEFAULT_LIMIT = {
            "MYSQL", "MARIADB", "TIDB", "OCEANBASE", "DORIS", "STARROCKS", "POSTGRESQL",
            "KINGBASE", "OPENGAUSS", "GAUSSDB", "COCKROACHDB", "REDSHIFT", "H2", "SQLITE",
            "DUCKDB", "DB2", "CLICKHOUSE", "HIVE", "PRESTO", "SNOWFLAKE"};

    /** Explicit dialect names with the SQL Server 1000-row INSERT limit. */
    private static final String[] SUPPORTED_WITH_1000_LIMIT = {"SQLSERVER", "MSSQL"};

    private MultiRowInsertSql() {
    }

    /**
     * Merges the chunk for the current connection's dialect, or returns {@code null} when merging
     * is disabled, the dialect is unknown or unsupported, or any statement does not have the plain
     * single-row INSERT shape — callers must then run the legacy path unchanged. Never throws.
     */
    public static List<String> mergeForCurrentDialect(List<String> sqls) {
        int maxRows = maxRowsForCurrentDialect();
        return maxRows <= 0 ? null : merge(sqls, maxRows);
    }

    /**
     * Resolves the per-statement row cap for the current connection: a positive value means the
     * dialect supports multi-row INSERT ... VALUES, {@code 0} means unknown (the caller may try
     * weaker probes), a negative value means the dialect must stay on the legacy path.
     */
    public static int maxRowsForCurrentDialect() {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
            return -1;
        }
        try {
            var connectInfo = Chat2DBContext.getConnectInfo();
            if (connectInfo == null) {
                return -1;
            }
            int byType = maxRowsByDbType(connectInfo.getDbType());
            if (byType != 0) {
                return byType;
            }
            return maxRowsByUrl(connectInfo.getUrl());
        } catch (Throwable dialectLookupFailure) {
            // Dialect detection must never break an import: unknown means legacy behaviour.
            return -1;
        }
    }

    /**
     * Dialect lookup by explicit Chat2DB type name; {@code 0} when the name is unmanaged (tests
     * and custom plugins) so the caller can fall through to the JDBC url probe.
     */
    static int maxRowsByDbType(String dbType) {
        String normalized = StringUtils.upperCase(StringUtils.trimToEmpty(dbType));
        if (normalized.isEmpty()) {
            return 0;
        }
        for (String supported : SUPPORTED_WITH_DEFAULT_LIMIT) {
            if (supported.equals(normalized)) {
                return DEFAULT_MAX_ROWS_PER_STATEMENT;
            }
        }
        for (String supported : SUPPORTED_WITH_1000_LIMIT) {
            if (supported.equals(normalized)) {
                return SQLSERVER_MAX_ROWS_PER_STATEMENT;
            }
        }
        if (isKnownUnsupportedType(normalized)) {
            return -1;
        }
        // Unmanaged type names (tests, custom plugins): try the druid mapping, then the url.
        String druidName;
        try {
            DbType druidType = JdbcUtils.parse2DruidDbType(dbType);
            druidName = druidType == null ? "" : druidType.name();
        } catch (Throwable mappingFailure) {
            return 0;
        }
        return switch (druidName) {
            case "mysql", "h2", "postgresql", "sqlite", "db2", "clickhouse", "hive",
                    "oceanbase", "presto", "trino", "edb", "gauss" -> DEFAULT_MAX_ROWS_PER_STATEMENT;
            case "sqlserver", "sqlserver2005", "sqlserver2012" -> SQLSERVER_MAX_ROWS_PER_STATEMENT;
            case "oracle", "dm", "oceanbase_oracle" -> -1;
            default -> 0;
        };
    }

    private static boolean isKnownUnsupportedType(String normalized) {
        // The Oracle family has no plain multi-row INSERT ... VALUES; document-only and
        // non-relational targets never go through the SQL insert chunk path anyway.
        return normalized.startsWith("ORACLE") || "DM".equals(normalized) || "OSCAR".equals(normalized)
                || "GBASE8S".equals(normalized) || "INFORMIX".equals(normalized)
                || "XUGU".equals(normalized) || "SUNDDB".equals(normalized)
                || "MONGODB".equals(normalized) || "ELASTICSEARCH".equals(normalized)
                || "REDIS".equals(normalized);
    }

    /** Last-resort dialect probe on the JDBC url sub-protocol. */
    static int maxRowsByUrl(String url) {
        String lower = StringUtils.lowerCase(StringUtils.trimToEmpty(url));
        if (!lower.startsWith("jdbc:")) {
            return -1;
        }
        String rest = lower.substring("jdbc:".length());
        int colon = rest.indexOf(':');
        String protocol = colon < 0 ? rest : rest.substring(0, colon);
        return switch (protocol) {
            case "mysql", "mariadb", "postgresql", "postgres", "h2", "sqlite", "duckdb",
                    "clickhouse", "db2", "hive2", "presto", "trino", "snowflake" -> DEFAULT_MAX_ROWS_PER_STATEMENT;
            case "sqlserver", "jtds" -> SQLSERVER_MAX_ROWS_PER_STATEMENT;
            default -> -1;
        };
    }

    /**
     * Pure merge over a chunk of statements: consecutive statements whose prefix (everything up to
     * and including the VALUES keyword, whitespace/case-insensitive) is identical are merged into
     * one multi-row statement, preserving statement order and splitting when a run reaches
     * {@code maxRowsPerStatement} rows or the 8 MB statement bound. Returns {@code null} when any
     * statement does not parse into the plain shape — the caller then keeps the legacy path.
     */
    public static List<String> merge(List<String> sqls, int maxRowsPerStatement) {
        if (sqls == null || sqls.isEmpty() || maxRowsPerStatement <= 0) {
            return null;
        }
        List<String> merged = new ArrayList<>(sqls.size());
        StringBuilder tuples = new StringBuilder(256);
        String runKey = null;
        String runPrefix = null;
        int runRows = 0;
        int runBytes = 0;
        for (String sql : sqls) {
            ParsedInsert parsed = parseSingleRowInsert(sql);
            if (parsed == null) {
                // One statement with an unexpected shape keeps the whole chunk on the legacy path.
                return null;
            }
            if (runKey != null && !runKey.equals(parsed.normalizedPrefix())) {
                merged.add(runPrefix + " " + VALUES_KEYWORD + " " + tuples);
                runKey = null;
            }
            if (runKey == null) {
                runKey = parsed.normalizedPrefix();
                runPrefix = parsed.prefix();
                tuples.setLength(0);
                runRows = 0;
                runBytes = 0;
            }
            if (runRows > 0 && (runRows >= maxRowsPerStatement
                    || runBytes + parsed.valuesPart().length() > MAX_BYTES_PER_STATEMENT)) {
                merged.add(runPrefix + " " + VALUES_KEYWORD + " " + tuples);
                tuples.setLength(0);
                runRows = 0;
                runBytes = 0;
            }
            if (runRows > 0) {
                tuples.append(", ");
            }
            tuples.append(parsed.valuesPart());
            runRows += parsed.tupleCount();
            runBytes += parsed.valuesPart().length() + 2;
        }
        if (runKey != null) {
            merged.add(runPrefix + " " + VALUES_KEYWORD + " " + tuples);
        }
        return merged;
    }

    /**
     * Parses one statement into (prefix, values part, tuple count), or {@code null} when it is
     * not a plain single-row INSERT: anything else (UPDATE, INSERT...SELECT, trailing ON
     * DUPLICATE/RETURNING clauses, several statements) refuses the merge.
     */
    private static ParsedInsert parseSingleRowInsert(String sql) {
        if (StringUtils.isBlank(sql)) {
            return null;
        }
        String trimmed = sql.trim();
        if (!trimmed.regionMatches(true, 0, "INSERT", 0, "INSERT".length())) {
            return null;
        }
        int valuesIndex = findValuesKeyword(trimmed);
        if (valuesIndex < 0) {
            return null;
        }
        String prefix = trimmed.substring(0, valuesIndex).trim();
        String valuesPart = trimmed.substring(valuesIndex + VALUES_KEYWORD.length()).trim();
        if (valuesPart.endsWith(";")) {
            valuesPart = valuesPart.substring(0, valuesPart.length() - 1).trim();
        }
        if (prefix.isEmpty() || !valuesPart.startsWith("(") || !valuesPart.endsWith(")")) {
            return null;
        }
        int tupleCount = countTuples(valuesPart);
        if (tupleCount <= 0) {
            return null;
        }
        return new ParsedInsert(prefix, normalizePrefix(prefix), valuesPart, tupleCount);
    }

    /**
     * Finds the VALUES keyword that starts the row literals: outside any quoted literal or
     * quoted identifier, on word boundaries, and immediately followed by a parenthesized tuple.
     * A column or table literally named VALUES is skipped because it is not followed by '('.
     */
    private static int findValuesKeyword(String sql) {
        int length = sql.length();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        boolean inBracket = false;
        for (int index = 0; index < length; index++) {
            char current = sql.charAt(index);
            if (inSingle) {
                // MySQL-style backslash escape; on dialects without it this can only ever fail
                // the parse (never corrupt data) because the merged form is verified by balance.
                if (current == '\\') {
                    index++;
                    continue;
                }
                if (current == '\'') {
                    if (index + 1 < length && sql.charAt(index + 1) == '\'') {
                        index++;
                        continue;
                    }
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (current == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inBacktick) {
                if (current == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (inBracket) {
                if (current == ']') {
                    inBracket = false;
                }
                continue;
            }
            switch (current) {
                case '\'' -> inSingle = true;
                case '"' -> inDouble = true;
                case '`' -> inBacktick = true;
                case '[' -> inBracket = true;
                default -> {
                    if (matchesValuesKeyword(sql, index)) {
                        int after = index + VALUES_KEYWORD.length();
                        while (after < length && Character.isWhitespace(sql.charAt(after))) {
                            after++;
                        }
                        if (after < length && sql.charAt(after) == '(') {
                            return index;
                        }
                    }
                }
            }
        }
        return -1;
    }

    private static boolean matchesValuesKeyword(String sql, int index) {
        if (!sql.regionMatches(true, index, VALUES_KEYWORD, 0, VALUES_KEYWORD.length())) {
            return false;
        }
        if (index > 0) {
            char before = sql.charAt(index - 1);
            if (!Character.isWhitespace(before) && before != ')' && before != '(' && before != ',') {
                return false;   // the tail of a longer identifier such as MYVALUES
            }
        }
        int end = index + VALUES_KEYWORD.length();
        if (end >= sql.length()) {
            return false;
        }
        char after = sql.charAt(end);
        return Character.isWhitespace(after) || after == '(';
    }

    /**
     * Verifies the text after VALUES is only top-level {@code (tuple), (tuple)} — quoted literals
     * and nested parens are consumed — and returns the tuple count, or -1 on anything else
     * (unbalanced parens, trailing ON DUPLICATE/RETURNING clauses, nested SELECT ...).
     */
    private static int countTuples(String values) {
        int depth = 0;
        int tuples = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        boolean inBracket = false;
        for (int index = 0; index < values.length(); index++) {
            char current = values.charAt(index);
            if (inSingle) {
                if (current == '\\') {
                    index++;
                    continue;
                }
                if (current == '\'') {
                    if (index + 1 < values.length() && values.charAt(index + 1) == '\'') {
                        index++;
                        continue;
                    }
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (current == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inBacktick) {
                if (current == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (inBracket) {
                if (current == ']') {
                    inBracket = false;
                }
                continue;
            }
            switch (current) {
                case '\'' -> inSingle = true;
                case '"' -> inDouble = true;
                case '`' -> inBacktick = true;
                case '[' -> inBracket = true;
                case '(' -> {
                    if (depth == 0) {
                        tuples++;
                    }
                    depth++;
                }
                case ')' -> {
                    depth--;
                    if (depth < 0) {
                        return -1;
                    }
                }
                default -> {
                    if (depth == 0 && !Character.isWhitespace(current) && current != ',') {
                        return -1;
                    }
                }
            }
        }
        return depth == 0 && tuples > 0 ? tuples : -1;
    }

    private static String normalizePrefix(String prefix) {
        return prefix.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private record ParsedInsert(String prefix, String normalizedPrefix, String valuesPart,
            int tupleCount) {
    }
}
