package ai.chat2db.plugin.bigquery;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the parallel keyset export contract this plugin declares: keyset-sharded exports are
 * enabled, so table exports may be split into key ranges and read in parallel.
 */
class BigQueryDBManagerExportCapabilityTest {

    @Test
    void declaresKeysetShardingExportCapability() {
        assertTrue(new BigQueryDBManager().getExportCapability().isKeysetSharding());
    }
}
