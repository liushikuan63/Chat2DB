package ai.chat2db.plugin.sqlserver;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the SQL Server import-resource probe.
 *
 * <p>No SQL Server instance is available in this environment, so the dialect queries are stubbed
 * while the probe's decisions are asserted: capacity comes from the DMV max_connections, an
 * availability-group secondary reports its redo lag, the trigger count is scoped to the requested
 * schema and escaped, and every failing probe is reported as unknown rather than fabricated.
 */
class SqlServerDBManagerProbeTest {

    @Test
    void reportsCapacityTriggersAndNonSecondaryRole() {
        StubSqlServerDBManager manager = new StubSqlServerDBManager();
        manager.maxConnections = 32767;
        manager.activeConnections = 12;
        manager.hadrEnabled = "1";
        manager.replicaRole = "PRIMARY";
        manager.triggerCount = 7;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "appdb", "dbo");

        assertTrue(snapshot.connectionCapacityKnown());
        assertEquals(32767, snapshot.maxConnections());
        assertEquals(12, snapshot.activeConnections());
        assertTrue(snapshot.replicationStatusKnown());
        assertFalse(snapshot.replica());
        assertNull(snapshot.replicationLagSeconds());
        assertTrue(snapshot.triggerStatusKnown());
        assertEquals(7, snapshot.triggerCount());
        assertFalse(snapshot.diskCapacityKnown());
    }

    @Test
    void scopesTheTriggerStatementToTheRequestedSchema() {
        String sql = SqlServerDBManager.buildTriggerCountSql("dbo");

        assertTrue(sql.endsWith("AND s.name = 'dbo'"), sql);
    }

    @Test
    void countsEveryTriggerWhenNoSchemaIsRequested() {
        String sql = SqlServerDBManager.buildTriggerCountSql(null);

        assertFalse(sql.contains("s.name ="), sql);
        assertEquals(sql, SqlServerDBManager.buildTriggerCountSql("   "));
    }

    /** A quote in the schema name must be doubled, never left to terminate the string literal. */
    @Test
    void doublesAQuoteInTheSchemaNameInsteadOfEndingTheLiteral() {
        String sql = SqlServerDBManager.buildTriggerCountSql("a'b");

        assertTrue(sql.endsWith("AND s.name = 'a''b'"), sql);
    }

    @Test
    void reportsSecondaryRedoLag() {
        StubSqlServerDBManager manager = new StubSqlServerDBManager();
        manager.hadrEnabled = "1";
        manager.replicaRole = "SECONDARY";
        manager.replicationLagSeconds = 15L;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "appdb", "dbo");

        assertTrue(snapshot.replicationStatusKnown());
        assertTrue(snapshot.replica());
        assertEquals(15L, snapshot.replicationLagSeconds());
    }

    /** A server without HADR is a legitimate primary, not an unknown. */
    @Test
    void treatsAServerWithoutHadrAsKnownNonReplica() {
        StubSqlServerDBManager manager = new StubSqlServerDBManager();
        manager.hadrEnabled = "0";

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "appdb", null);

        assertTrue(snapshot.replicationStatusKnown());
        assertFalse(snapshot.replica());
        assertFalse(manager.queriedReplicaDmv, "a non-HADR server must not query the replica DMVs");
    }

    @Test
    void reportsEveryFailingProbeAsUnknownInsteadOfInventingValues() {
        StubSqlServerDBManager manager = new StubSqlServerDBManager();
        manager.failCapacity = true;
        manager.failReplication = true;
        manager.failTriggers = true;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "appdb", "dbo");

        assertFalse(snapshot.connectionCapacityKnown());
        assertEquals(0, snapshot.maxConnections());
        assertFalse(snapshot.replicationStatusKnown());
        assertFalse(snapshot.triggerStatusKnown());
        assertTrue(snapshot.evidence().contains("DMVs may need VIEW SERVER STATE"), snapshot.evidence());
        assertTrue(snapshot.evidence().contains("replication status unavailable"), snapshot.evidence());
        assertTrue(snapshot.evidence().contains("trigger metadata unavailable"), snapshot.evidence());
    }

    private static final class StubSqlServerDBManager extends SqlServerDBManager {

        private int maxConnections = 100;
        private int activeConnections = 1;
        private String hadrEnabled = "0";
        private String replicaRole = "PRIMARY";
        private Long replicationLagSeconds;
        private int triggerCount;
        private boolean queriedReplicaDmv;
        private boolean failCapacity;
        private boolean failReplication;
        private boolean failTriggers;

        @Override
        int queryInt(Connection connection, String sql) throws SQLException {
            if (failCapacity) {
                throw new SQLException("fixture capacity failure");
            }
            return sql.contains("dm_os_sys_info") ? maxConnections : activeConnections;
        }

        @Override
        String queryString(Connection connection, String sql) throws SQLException {
            if (sql.contains("IsHadrEnabled")) {
                if (failReplication) {
                    throw new SQLException("fixture HADR failure");
                }
                return hadrEnabled;
            }
            queriedReplicaDmv = true;
            return replicaRole;
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
            return triggerCount;
        }
    }
}
