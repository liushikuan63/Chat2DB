package ai.chat2db.plugin.kingbase;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the Kingbase import-resource probe.
 *
 * <p>No Kingbase server is available in this environment, so the catalog read is stubbed while the
 * decisions are asserted: the trigger count comes from the pg_trigger/pg_class/pg_namespace join
 * this plugin already uses for trigger metadata, and capacity and replication stay unknown because
 * this plugin relies on no pg_settings, pg_stat_activity or recovery view.
 */
class KingBaseDBManagerProbeTest {

    @Test
    void reportsTriggersAndLeavesUnreliedOnFactsUnknown() {
        StubKingBaseDBManager manager = new StubKingBaseDBManager();
        manager.triggerCount = 6;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "test", "app");

        assertTrue(snapshot.triggerStatusKnown());
        assertEquals(6, snapshot.triggerCount());
        assertEquals("app", manager.probedSchema);
        assertFalse(snapshot.connectionCapacityKnown());
        assertEquals(0, snapshot.maxConnections());
        assertFalse(snapshot.replicationStatusKnown());
        assertFalse(snapshot.diskCapacityKnown());
        assertTrue(snapshot.evidence().contains("unknown"), snapshot.evidence());
    }

    @Test
    void reportsUnknownTriggersWhenTheCatalogFails() {
        StubKingBaseDBManager manager = new StubKingBaseDBManager();
        manager.fail = true;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "test", "app");

        assertFalse(snapshot.triggerStatusKnown());
        assertEquals(0, snapshot.triggerCount());
        assertTrue(snapshot.evidence().contains("trigger metadata unavailable"), snapshot.evidence());
    }

    /** A blank schema must reach the query as null so its COALESCE falls back to current_schema(). */
    @Test
    void keepsABlankSchemaAsNullSoTheSqlFallsBackToTheCurrentSchema() {
        StubKingBaseDBManager manager = new StubKingBaseDBManager();

        manager.probeImportResources(null, "test", "   ");

        assertTrue(manager.probedSchema == null || manager.probedSchema.isBlank(),
                "a blank schema must not be replaced by a fabricated schema name, saw " + manager.probedSchema);
    }

    private static final class StubKingBaseDBManager extends KingBaseDBManager {

        private int triggerCount;
        private boolean fail;
        private String probedSchema;

        @Override
        int queryTriggerCount(Connection connection, String schemaName) throws SQLException {
            probedSchema = schemaName == null ? null : schemaName.trim();
            if (fail) {
                throw new SQLException("fixture trigger failure");
            }
            return triggerCount;
        }
    }
}
