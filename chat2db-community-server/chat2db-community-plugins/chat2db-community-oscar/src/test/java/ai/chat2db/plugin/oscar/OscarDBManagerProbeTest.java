package ai.chat2db.plugin.oscar;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the Oscar import-resource probe, which lives on the shared base manager so
 * every Oscar dialect inherits it.
 *
 * <p>This plugin relies on no session, process or parameter view, so connection capacity and
 * replication must be reported unknown rather than invented. The trigger count is the one fact the
 * probe may claim, and it needs an explicit owner: guessing a current-schema function this plugin
 * does not use would be fabrication.
 */
class OscarDBManagerProbeTest {

    @Test
    void countsTriggersForTheRequestedOwner() {
        StubOscarDBManager manager = new StubOscarDBManager();
        manager.triggerCount = 2;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "DB", "APP");

        assertTrue(snapshot.triggerStatusKnown());
        assertEquals(2, snapshot.triggerCount());
        assertFalse(snapshot.connectionCapacityKnown());
        assertEquals(0, snapshot.maxConnections());
        assertFalse(snapshot.replicationStatusKnown());
        assertFalse(snapshot.diskCapacityKnown());
        assertTrue(snapshot.evidence().contains("unknown"), snapshot.evidence());
    }

    @Test
    void needsAnExplicitOwnerRatherThanGuessingTheCurrentSchema() {
        StubOscarDBManager manager = new StubOscarDBManager();
        manager.triggerCount = 99;

        ImportResourceSnapshot blank = manager.probeImportResources(null, "DB", "   ");

        assertFalse(blank.triggerStatusKnown());
        assertEquals(0, blank.triggerCount());
        assertTrue(blank.evidence().contains("requires an explicit owner"), blank.evidence());
        assertFalse(manager.queried, "no catalog read may happen without an owner");
    }

    @Test
    void reportsUnknownTriggersWhenTheCatalogFails() {
        StubOscarDBManager manager = new StubOscarDBManager();
        manager.fail = true;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "DB", "APP");

        assertFalse(snapshot.triggerStatusKnown());
        assertEquals(0, snapshot.triggerCount());
        assertTrue(snapshot.evidence().contains("trigger metadata unavailable"), snapshot.evidence());
    }

    /** A quote in the owner name must be doubled, never left to terminate the string literal. */
    @Test
    void doublesAQuoteInTheOwnerNameInsteadOfEndingTheLiteral() {
        String sql = OscarBaseDBManager.buildTriggerCountSql("a'b");

        assertEquals("SELECT COUNT(*) FROM ALL_TRIGGERS WHERE OWNER = 'a''b'", sql);
    }

    /** The concrete manager is the one apps use, so the inherited probe is asserted through it. */
    private static final class StubOscarDBManager extends OscarDBManager {

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
