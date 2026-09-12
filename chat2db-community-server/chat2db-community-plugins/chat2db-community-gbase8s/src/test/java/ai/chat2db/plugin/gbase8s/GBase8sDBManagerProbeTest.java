package ai.chat2db.plugin.gbase8s;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the GBase 8s import-resource probe.
 *
 * <p>GBase 8s answers none of the admission resource questions, and the probe must say so
 * explicitly with the dialect-specific reasons instead of leaving the reader to guess whether the
 * probe failed.
 */
class GBase8sDBManagerProbeTest {

    @Test
    void reportsEveryFactUnknown() {
        ImportResourceSnapshot snapshot = new GBase8sDBManager().probeImportResources(null, "db", "schema");

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
        String evidence = new GBase8sDBManager().probeImportResources(null, "db", "schema").evidence();

        assertTrue(evidence.contains("Informix-derived"), evidence);
        assertTrue(evidence.contains("sysmaster"), evidence);
        assertTrue(evidence.contains("not verified"), evidence);
        assertTrue(evidence.contains("unverified queries"), evidence);
    }
}
