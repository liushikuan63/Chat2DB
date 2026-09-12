package ai.chat2db.plugin.oracle;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the Oracle import-resource probe.
 *
 * <p>No Oracle server is available in this environment, so the dialect queries are stubbed while
 * the probe's decisions are asserted: capacity comes from the sessions parameter, a physical standby
 * reports its apply lag, trigger risk is scoped to the requested owner, and every failing probe is
 * reported as unknown with an explanation rather than a fabricated value.
 */
class OracleDBManagerProbeTest {

    @Test
    void reportsCapacityTriggersAndNonStandbyRole() {
        StubOracleDBManager manager = new StubOracleDBManager();
        manager.maxConnections = 300;
        manager.activeConnections = 42;
        manager.databaseRole = "PRIMARY";
        manager.triggerCount = 5;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "ORCL", "APP");

        assertTrue(snapshot.connectionCapacityKnown());
        assertEquals(300, snapshot.maxConnections());
        assertEquals(42, snapshot.activeConnections());
        assertTrue(snapshot.replicationStatusKnown());
        assertFalse(snapshot.replica());
        assertNull(snapshot.replicationLagSeconds());
        assertTrue(snapshot.triggerStatusKnown());
        assertEquals(5, snapshot.triggerCount());
        assertEquals("APP", manager.probedOwner);
        assertFalse(snapshot.diskCapacityKnown());
    }

    @Test
    void reportsStandbyApplyLag() {
        StubOracleDBManager manager = new StubOracleDBManager();
        manager.databaseRole = "PHYSICAL STANDBY";
        manager.replicationLagSeconds = 27L;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "ORCL", "APP");

        assertTrue(snapshot.replicationStatusKnown());
        assertTrue(snapshot.replica());
        assertEquals(27L, snapshot.replicationLagSeconds());
    }

    @Test
    void reportsEveryFailingProbeAsUnknownInsteadOfInventingValues() {
        StubOracleDBManager manager = new StubOracleDBManager();
        manager.failCapacity = true;
        manager.failReplication = true;
        manager.failTriggers = true;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "ORCL", null);

        assertFalse(snapshot.connectionCapacityKnown());
        assertEquals(0, snapshot.maxConnections());
        assertFalse(snapshot.replicationStatusKnown());
        assertFalse(snapshot.triggerStatusKnown());
        assertTrue(snapshot.evidence().contains("connection capacity unavailable"), snapshot.evidence());
        assertTrue(snapshot.evidence().contains("replication status unavailable"), snapshot.evidence());
        assertTrue(snapshot.evidence().contains("trigger metadata unavailable"), snapshot.evidence());
    }

    /** A blank owner must fall back to the connected schema instead of scanning every schema. */
    @Test
    void fallsBackToTheConnectedSchemaWhenNoOwnerIsGiven() {
        StubOracleDBManager manager = new StubOracleDBManager();
        manager.currentSchema = "CONNECTED_APP";

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "ORCL", "   ");

        assertTrue(snapshot.triggerStatusKnown());
        assertEquals("CONNECTED_APP", manager.probedOwner);
    }

    /** A quote in the owner name must be doubled, never left to terminate the string literal. */
    @Test
    void doublesAQuoteInTheOwnerNameInsteadOfEndingTheLiteral() {
        String sql = OracleDBManager.buildTriggerCountSql("a'b");

        assertEquals("SELECT COUNT(*) FROM all_triggers WHERE OWNER = 'a''b'", sql);
    }

    private static final class StubOracleDBManager extends OracleDBManager {

        private int maxConnections = 100;
        private int activeConnections = 1;
        private String databaseRole = "PRIMARY";
        private Long replicationLagSeconds;
        private int triggerCount;
        private String currentSchema = "APP";
        private String probedOwner;
        private boolean failCapacity;
        private boolean failReplication;
        private boolean failTriggers;

        @Override
        int queryInt(Connection connection, String sql) throws SQLException {
            if (failCapacity) {
                throw new SQLException("fixture capacity failure");
            }
            return sql.contains("v$parameter") ? maxConnections : activeConnections;
        }

        @Override
        String queryString(Connection connection, String sql) throws SQLException {
            if (sql.contains("v$database")) {
                if (failReplication) {
                    throw new SQLException("fixture role failure");
                }
                return databaseRole;
            }
            return currentSchema;
        }

        @Override
        Long queryNullableLong(Connection connection, String sql) throws SQLException {
            return replicationLagSeconds;
        }

        @Override
        int queryCount(Connection connection, String sql) throws SQLException {
            if (failTriggers) {
                throw new SQLException("fixture trigger failure");
            }
            probedOwner = sql.contains("'APP'") ? "APP"
                    : sql.contains("'CONNECTED_APP'") ? "CONNECTED_APP" : null;
            return triggerCount;
        }
    }
}
