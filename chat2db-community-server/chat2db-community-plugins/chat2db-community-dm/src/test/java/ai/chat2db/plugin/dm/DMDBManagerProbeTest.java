package ai.chat2db.plugin.dm;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the DM import-resource probe.
 *
 * <p>No DM server is available in this environment, so the catalog reads are stubbed while every
 * decision is asserted: capacity comes from V$DM_INI's MAX_SESSIONS against V$SESSIONS, triggers are
 * scoped to the requested owner with a fallback to the connected schema, replication stays unknown
 * because this plugin relies on no standby-lag view, and a failing probe is reported as unknown
 * rather than fabricated.
 */
class DMDBManagerProbeTest {

    @Test
    void reportsCapacityAndScopedTriggers() {
        StubDMDBManager manager = new StubDMDBManager();
        manager.maxSessions = 100;
        manager.activeSessions = 9;
        manager.triggerCount = 3;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "DAMENG", "APP");

        assertTrue(snapshot.connectionCapacityKnown());
        assertEquals(100, snapshot.maxConnections());
        assertEquals(9, snapshot.activeConnections());
        assertFalse(snapshot.replicationStatusKnown());
        assertNull(snapshot.replicationLagSeconds());
        assertTrue(snapshot.triggerStatusKnown());
        assertEquals(3, snapshot.triggerCount());
        assertEquals("APP", manager.probedOwner);
        assertFalse(snapshot.diskCapacityKnown());
    }

    /** No standby-lag view is relied on, so replication must be reported unknown with a reason. */
    @Test
    void keepsReplicationUnknownBecauseNoStandbyViewIsUsed() {
        StubDMDBManager manager = new StubDMDBManager();

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "DAMENG", "APP");

        assertFalse(snapshot.replicationStatusKnown());
        assertFalse(snapshot.replica());
        assertTrue(snapshot.evidence().contains("replication status is not exposed"), snapshot.evidence());
    }

    @Test
    void fallsBackToTheConnectedSchemaWhenNoOwnerIsGiven() {
        StubDMDBManager manager = new StubDMDBManager();
        manager.currentSchema = "CONNECTED_APP";

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "DAMENG", "   ");

        assertTrue(snapshot.triggerStatusKnown());
        assertEquals("CONNECTED_APP", manager.probedOwner);
    }

    @Test
    void reportsEveryFailingProbeAsUnknownInsteadOfInventingValues() {
        StubDMDBManager manager = new StubDMDBManager();
        manager.failCapacity = true;
        manager.failTriggers = true;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "DAMENG", "APP");

        assertFalse(snapshot.connectionCapacityKnown());
        assertEquals(0, snapshot.maxConnections());
        assertFalse(snapshot.triggerStatusKnown());
        assertTrue(snapshot.evidence().contains("connection capacity unavailable"), snapshot.evidence());
        assertTrue(snapshot.evidence().contains("trigger metadata unavailable"), snapshot.evidence());
    }

    /** A quote in the owner name must be doubled, never left to terminate the string literal. */
    @Test
    void doublesAQuoteInTheOwnerNameInsteadOfEndingTheLiteral() {
        String sql = DMDBManager.buildTriggerCountSql("a'b");

        assertEquals("SELECT COUNT(*) FROM ALL_TRIGGERS WHERE OWNER = 'a''b'", sql);
    }

    private static final class StubDMDBManager extends DMDBManager {

        private int maxSessions = 100;
        private int activeSessions = 1;
        private int triggerCount;
        private String currentSchema = "APP";
        private String probedOwner;
        private boolean failCapacity;
        private boolean failTriggers;

        @Override
        int queryInt(Connection connection, String sql) throws SQLException {
            if (failCapacity) {
                throw new SQLException("fixture capacity failure");
            }
            return sql.contains("V$DM_INI") ? maxSessions : activeSessions;
        }

        @Override
        String queryString(Connection connection, String sql) throws SQLException {
            return currentSchema;
        }

        @Override
        String resolveTriggerOwner(Connection connection, String schemaName) throws SQLException {
            probedOwner = schemaName == null || schemaName.isBlank() ? currentSchema : schemaName.trim();
            return probedOwner;
        }

        @Override
        int queryCount(Connection connection, String sql) throws SQLException {
            if (failTriggers) {
                throw new SQLException("fixture trigger failure");
            }
            return triggerCount;
        }
    }
}
