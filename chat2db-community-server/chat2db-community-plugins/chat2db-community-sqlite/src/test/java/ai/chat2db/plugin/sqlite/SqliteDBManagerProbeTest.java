package ai.chat2db.plugin.sqlite;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the SQLite import-resource probe.
 *
 * <p>The SQLite JDBC driver is resolved at runtime rather than declared for tests, so the probe is
 * verified in two halves: the generated catalog statement is asserted directly (it is the part that
 * can be wrong), and the snapshot semantics are asserted through a stub that fails the query on
 * demand. SQLite is embedded and single-writer, so capacity and replication must stay unknown.
 */
class SqliteDBManagerProbeTest {

    @Test
    void countsEveryTriggerWhenNoTableIsRequested() {
        String sql = SqliteDBManager.buildTriggerCountSql(null);

        assertEquals("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'", sql);
    }

    @Test
    void scopesTheStatementToTheRequestedTableAndTreatsBlankAsAbsent() {
        assertEquals("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'ORDERS'",
                SqliteDBManager.buildTriggerCountSql("ORDERS"));
        assertEquals(SqliteDBManager.buildTriggerCountSql(null), SqliteDBManager.buildTriggerCountSql("   "));
    }

    /** A quote in the table name must be doubled, never left to terminate the string literal. */
    @Test
    void doublesAQuoteInTheTableNameInsteadOfEndingTheLiteral() {
        String sql = SqliteDBManager.buildTriggerCountSql("a'b");

        assertEquals("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'a''b'", sql);
        assertFalse(sql.contains("'a'b'"), "an unescaped quote would end the literal early");
    }

    @Test
    void reportsTriggerCountAndLeavesEmbeddedIrrelevantFactsUnknown() {
        StubSqliteDBManager manager = new StubSqliteDBManager();
        manager.count = 4;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, null, null);

        assertTrue(snapshot.triggerStatusKnown());
        assertEquals(4, snapshot.triggerCount());
        assertFalse(snapshot.connectionCapacityKnown());
        assertEquals(0, snapshot.maxConnections());
        assertFalse(snapshot.replicationStatusKnown());
        assertFalse(snapshot.diskCapacityKnown());
        assertTrue(snapshot.evidence().contains("embedded"));
        assertTrue(manager.probedSql.contains("sqlite_master"));
    }

    @Test
    void reportsUnknownTriggersWhenTheCatalogQueryCannotRun() {
        StubSqliteDBManager manager = new StubSqliteDBManager();
        manager.fail = true;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, null, null);

        assertFalse(snapshot.triggerStatusKnown());
        assertEquals(0, snapshot.triggerCount());
        assertTrue(snapshot.evidence().contains("trigger metadata unavailable"), snapshot.evidence());
    }

    private static final class StubSqliteDBManager extends SqliteDBManager {

        private int count;
        private boolean fail;
        private String probedSql;

        @Override
        int queryCount(Connection connection, String sql) throws SQLException {
            probedSql = sql;
            if (fail) {
                throw new SQLException("fixture catalog failure");
            }
            return count;
        }
    }
}
