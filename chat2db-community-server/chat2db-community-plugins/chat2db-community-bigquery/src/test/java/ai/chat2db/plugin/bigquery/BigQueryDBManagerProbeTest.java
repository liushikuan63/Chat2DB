package ai.chat2db.plugin.bigquery;

import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract coverage for the BigQuery import-resource probe.
 *
 * <p>BigQuery answers none of the admission resource questions, and the probe must say so
 * explicitly with the dialect-specific reasons instead of leaving the reader to guess whether the
 * probe failed.
 */
class BigQueryDBManagerProbeTest {

    @Test
    void reportsEveryFactUnknown() {
        ImportResourceSnapshot snapshot = new BigQueryDBManager().probeImportResources(null, "db", "schema");

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
        String evidence = new BigQueryDBManager().probeImportResources(null, "db", "schema").evidence();

        assertTrue(evidence.contains("serverless"), evidence);
        assertTrue(evidence.contains("no fixed connection pool"), evidence);
        assertTrue(evidence.contains("not verified against a live"), evidence);
        assertTrue(evidence.contains("does not issue unverified queries"), evidence);
    }
}
