package ai.chat2db.spi.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The multi-row INSERT merge: consecutive same-shape single-row INSERTs collapse into one
 * multi-row statement, statement order across tables is preserved, quoted literals and embedded
 * VALUES keywords survive, and anything unexpected refuses the merge so the caller stays on the
 * legacy one-statement-per-row path.
 */
class MultiRowInsertSqlTest {

    @Test
    void mergesConsecutiveSameTableInserts() {
        List<String> merged = MultiRowInsertSql.merge(List.of(
                "INSERT INTO `t` (`id`, `name`) VALUES (1, 'a')",
                "INSERT INTO `t` (`id`, `name`) VALUES (2, 'b')",
                "INSERT INTO `t` (`id`, `name`) VALUES (3, 'c')"), 5_000);
        assertEquals(List.of("INSERT INTO `t` (`id`, `name`) VALUES (1, 'a'), (2, 'b'), (3, 'c')"),
                merged);
    }

    @Test
    void keepsStatementOrderAcrossDifferentTables() {
        List<String> merged = MultiRowInsertSql.merge(List.of(
                "INSERT INTO a (id) VALUES (1)",
                "INSERT INTO a (id) VALUES (2)",
                "INSERT INTO b (id) VALUES (9)",
                "INSERT INTO a (id) VALUES (3)"), 5_000);
        assertEquals(3, merged.size());
        assertEquals("INSERT INTO a (id) VALUES (1), (2)", merged.get(0));
        assertEquals("INSERT INTO b (id) VALUES (9)", merged.get(1));
        assertEquals("INSERT INTO a (id) VALUES (3)", merged.get(2));
    }

    @Test
    void splitsRunsAtTheRowCap() {
        List<String> merged = MultiRowInsertSql.merge(List.of(
                "INSERT INTO a (id) VALUES (1)",
                "INSERT INTO a (id) VALUES (2)",
                "INSERT INTO a (id) VALUES (3)"), 2);
        assertEquals(2, merged.size());
        assertEquals("INSERT INTO a (id) VALUES (1), (2)", merged.get(0));
        assertEquals("INSERT INTO a (id) VALUES (3)", merged.get(1));
    }

    @Test
    void survivesCommasQuotesAndEmbeddedValuesKeywords() {
        List<String> merged = MultiRowInsertSql.merge(List.of(
                "INSERT INTO t (a, b) VALUES ('x,y', 'it''s VALUES (not a keyword)')",
                "INSERT INTO t (a, b) VALUES ('ok', NULL)"), 5_000);
        assertEquals(1, merged.size());
        assertEquals("INSERT INTO t (a, b) VALUES ('x,y', 'it''s VALUES (not a keyword)'), ('ok', NULL)",
                merged.get(0));
    }

    @Test
    void refusesAnythingButPlainSingleRowInserts() {
        assertNull(MultiRowInsertSql.merge(List.of("UPDATE t SET a = 1"), 5_000));
        assertNull(MultiRowInsertSql.merge(List.of("INSERT INTO t (a) SELECT 1"), 5_000));
        assertNull(MultiRowInsertSql.merge(List.of(
                "INSERT INTO t (a) VALUES (1) ON DUPLICATE KEY UPDATE a = 1"), 5_000));
        assertNull(MultiRowInsertSql.merge(List.of(
                "INSERT INTO a (id) VALUES (1)", "DELETE FROM a WHERE id = 1"), 5_000));
        assertNull(MultiRowInsertSql.merge(List.of(), 5_000));
        assertNull(MultiRowInsertSql.merge(null, 5_000));
    }

    @Test
    void dialectResolutionPrefersExplicitTypesThenDruidThenUrl() {
        assertEquals(MultiRowInsertSql.DEFAULT_MAX_ROWS_PER_STATEMENT,
                MultiRowInsertSql.maxRowsByDbType("MYSQL"));
        assertEquals(MultiRowInsertSql.SQLSERVER_MAX_ROWS_PER_STATEMENT,
                MultiRowInsertSql.maxRowsByDbType("SQLSERVER"));
        assertEquals(-1, MultiRowInsertSql.maxRowsByDbType("ORACLE"));
        assertEquals(-1, MultiRowInsertSql.maxRowsByDbType("DM"));
        // Unmanaged test types fall through (0) unless the druid mapping or the url decides.
        assertEquals(0, MultiRowInsertSql.maxRowsByDbType("PARALLEL_IMPORT_TEST"));
        assertEquals(MultiRowInsertSql.DEFAULT_MAX_ROWS_PER_STATEMENT,
                MultiRowInsertSql.maxRowsByUrl("jdbc:mysql://127.0.0.1:3306/db"));
        assertEquals(MultiRowInsertSql.DEFAULT_MAX_ROWS_PER_STATEMENT,
                MultiRowInsertSql.maxRowsByUrl("jdbc:h2:mem:test"));
        assertEquals(MultiRowInsertSql.SQLSERVER_MAX_ROWS_PER_STATEMENT,
                MultiRowInsertSql.maxRowsByUrl("jdbc:sqlserver://host;databaseName=db"));
        assertEquals(-1, MultiRowInsertSql.maxRowsByUrl("jdbc:oracle:thin:@host:1521:orcl"));
        assertEquals(-1, MultiRowInsertSql.maxRowsByUrl(null));
    }
}
