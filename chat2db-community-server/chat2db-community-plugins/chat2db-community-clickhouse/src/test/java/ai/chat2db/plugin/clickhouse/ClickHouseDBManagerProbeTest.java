package ai.chat2db.plugin.clickhouse;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the ClickHouse import-resource probe.
 *
 * <p>This plugin relies on no system.metrics or system.processes view, so connection capacity and
 * replication must be reported unknown rather than invented. The trigger count comes from the
 * INFORMATION_SCHEMA.TRIGGERS view this plugin already reads for trigger metadata, and it needs an
 * explicit schema because there is no meaningful default here.
 */
class ClickHouseDBManagerProbeTest {

    @Test
    void countsTriggersForTheRequestedSchema() {
        StubClickHouseDBManager manager = new StubClickHouseDBManager();
        manager.triggerCount = 3;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "default", "app");

        assertTrue(snapshot.triggerStatusKnown());
        assertEquals(3, snapshot.triggerCount());
        assertFalse(snapshot.connectionCapacityKnown());
        assertEquals(0, snapshot.maxConnections());
        assertFalse(snapshot.replicationStatusKnown());
        assertFalse(snapshot.diskCapacityKnown());
        assertTrue(snapshot.evidence().contains("unknown"), snapshot.evidence());
    }

    @Test
    void needsAnExplicitSchemaRatherThanGuessingTheCurrentDatabase() {
        StubClickHouseDBManager manager = new StubClickHouseDBManager();
        manager.triggerCount = 99;

        ImportResourceSnapshot blank = manager.probeImportResources(null, "default", "  ");

        assertFalse(blank.triggerStatusKnown());
        assertEquals(0, blank.triggerCount());
        assertTrue(blank.evidence().contains("requires an explicit schema"), blank.evidence());
        assertFalse(manager.queried, "no catalog read may happen without a schema");
    }

    @Test
    void reportsUnknownTriggersWhenTheCatalogFails() {
        StubClickHouseDBManager manager = new StubClickHouseDBManager();
        manager.fail = true;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "default", "app");

        assertFalse(snapshot.triggerStatusKnown());
        assertEquals(0, snapshot.triggerCount());
        assertTrue(snapshot.evidence().contains("trigger metadata unavailable"), snapshot.evidence());
    }

    /** A quote in the schema name must be doubled, never left to terminate the string literal. */
    @Test
    void doublesAQuoteInTheSchemaNameInsteadOfEndingTheLiteral() {
        String sql = ClickHouseDBManager.buildTriggerCountSql("a'b");

        assertEquals("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = 'a''b'", sql);
    }

    private static final class StubClickHouseDBManager extends ClickHouseDBManager {

        private int triggerCount;
        private boolean fail;
        private boolean queried;

        @Override
        int queryCount(Connection connection, String sql) throws SQLException {
            queried = true;
            if (fail) {
                throw new SQLException("fixture trigger failure");
            }
            return triggerCount;
        }
    }
}
