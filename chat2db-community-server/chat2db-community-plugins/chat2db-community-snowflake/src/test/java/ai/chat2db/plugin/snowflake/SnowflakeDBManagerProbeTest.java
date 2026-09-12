package ai.chat2db.plugin.snowflake;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the Snowflake import-resource probe.
 *
 * <p>Snowflake answers none of the admission resource questions, and the probe must say so
 * explicitly with the dialect-specific reasons instead of leaving the reader to guess whether the
 * probe failed.
 */
class SnowflakeDBManagerProbeTest {

    @Test
    void reportsEveryFactUnknown() {
        ImportResourceSnapshot snapshot = new SnowflakeDBManager().probeImportResources(null, "db", "schema");

        assertFalse(snapshot.connectionCapacityKnown());
        assertEquals(0, snapshot.maxConnections());
        assertEquals(0, snapshot.activeConnections());
        assertFalse(snapshot.replicationStatusKnown());
        assertFalse(snapshot.replica());
        assertNull(snapshot.replicationLagSeconds());
        assertFalse(snapshot.triggerStatusKnown());
        assertEquals(0, snapshot.triggerCount());
        assertFalse(snapshot.diskCapacityKnown());
    }

    /** The reasons are dialect-specific, so the evidence must name them rather than be generic. */
    @Test
    void namesTheDialectSpecificReasonsInTheEvidence() {
        String evidence = new SnowflakeDBManager().probeImportResources(null, "db", "schema").evidence();

        assertTrue(evidence.contains("lags by up to 3 hours"), evidence);
        assertTrue(evidence.contains("5-minute intervals"), evidence);
        assertTrue(evidence.contains("no replication-lag view"), evidence);
        assertTrue(evidence.contains("no row-level"), evidence);
        assertTrue(evidence.contains("disk free space"), evidence);
    }
}
