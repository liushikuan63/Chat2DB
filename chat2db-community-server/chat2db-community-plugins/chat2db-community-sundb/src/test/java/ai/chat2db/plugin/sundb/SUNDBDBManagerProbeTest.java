package ai.chat2db.plugin.sundb;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the SUNDB import-resource probe.
 *
 * <p>This plugin relies on no session, process or parameter view, so connection capacity and
 * replication must be reported unknown rather than invented. The trigger count is the one fact the
 * probe may claim, and it needs an explicit owner: guessing a current-schema function this plugin
 * does not use would be fabrication.
 */
class SUNDBDBManagerProbeTest {

    @Test
    void countsTriggersForTheRequestedOwner() {
        StubSUNDBDBManager manager = new StubSUNDBDBManager();
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
        StubSUNDBDBManager manager = new StubSUNDBDBManager();
        manager.triggerCount = 99;

        ImportResourceSnapshot blank = manager.probeImportResources(null, "DB", "   ");

        assertFalse(blank.triggerStatusKnown());
        assertEquals(0, blank.triggerCount());
        assertTrue(blank.evidence().contains("requires an explicit owner"), blank.evidence());
        assertFalse(manager.queried, "no catalog read may happen without an owner");
    }

    @Test
    void reportsUnknownTriggersWhenTheCatalogFails() {
        StubSUNDBDBManager manager = new StubSUNDBDBManager();
        manager.fail = true;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "DB", "APP");

        assertFalse(snapshot.triggerStatusKnown());
        assertEquals(0, snapshot.triggerCount());
        assertTrue(snapshot.evidence().contains("trigger metadata unavailable"), snapshot.evidence());
    }

    /** A quote in the owner name must be doubled, never left to terminate the string literal. */
    @Test
    void doublesAQuoteInTheOwnerNameInsteadOfEndingTheLiteral() {
        String sql = SUNDBDBManager.buildTriggerCountSql("a'b");

        assertEquals("SELECT COUNT(*) FROM ALL_TRIGGERS WHERE OWNER = 'a''b'", sql);
    }

    private static final class StubSUNDBDBManager extends SUNDBDBManager {

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
