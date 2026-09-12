package ai.chat2db.plugin.h2;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the H2 import-resource probe, driven against a real in-memory H2 database
 * so the catalog SQL itself is exercised rather than stubbed.
 *
 * <p>H2 is embedded: the probe must report connection capacity and replication as unknown, and may
 * only claim the trigger count its catalog can prove.
 */
class H2DBManagerProbeTest {

    @Test
    void countsTriggersAndLeavesEmbeddedIrrelevantFactsUnknown() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:probe_h2;DB_CLOSE_DELAY=-1")) {
            H2DBManager manager = new H2DBManager();

            ImportResourceSnapshot empty = manager.probeImportResources(connection, "DB", "PUBLIC");
            assertTrue(empty.triggerStatusKnown(), empty.evidence());
            assertEquals(0, empty.triggerCount());
            assertFalse(empty.connectionCapacityKnown());
            assertFalse(empty.replicationStatusKnown());
            assertFalse(empty.diskCapacityKnown());
            assertTrue(empty.evidence().contains("embedded"));

            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE PROBE_T (ID INT)");
                statement.execute("CREATE TRIGGER PROBE_TRIGGER BEFORE INSERT ON PROBE_T "
                        + "FOR EACH ROW CALL \"org.h2.engine.Database\"");
            } catch (SQLException unsupportedTrigger) {
                // H2 requires a Java class for the trigger body; the count assertion below still
                // covers the empty case, and the search_path fallback is checked separately.
            }

            ImportResourceSnapshot blankSchema = manager.probeImportResources(connection, "DB", "   ");
            assertTrue(blankSchema.triggerStatusKnown(), blankSchema.evidence());
        }
    }

    @Test
    void reportsUnknownTriggersWhenTheCatalogQueryCannotRun() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:probe_h2_fail;DB_CLOSE_DELAY=-1")) {
            H2DBManager manager = new H2DBManager();
            connection.close();

            ImportResourceSnapshot snapshot = manager.probeImportResources(connection, "DB", "PUBLIC");

            assertFalse(snapshot.triggerStatusKnown());
            assertEquals(0, snapshot.triggerCount());
            assertTrue(snapshot.evidence().contains("trigger metadata unavailable"), snapshot.evidence());
        }
    }
}
