package ai.chat2db.plugin.presto;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;

import java.sql.Connection;

public class PrestoDBManager extends DefaultDBManager implements IDbManager {

    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.KEYSET_SHARDING;
    }

    /**
     * Presto answers none of the admission resource questions, and the reasons are inherent to the
     * dialect rather than a failed probe: it is a federated query engine whose sessions are
     * short-lived queries against connectors rather than a bounded connection pool, so connection
     * capacity is not a Presto concept; Presto has no replication; it has no row-level DML triggers,
     * so trigger ordering cannot be a risk; and it exposes neither connector-side disk space nor a
     * live resource view through SQL. Reporting this explicitly keeps the admission report honest
     * instead of leaving the reader to guess whether the probe failed.
     */
    @Override
    public ai.chat2db.spi.model.imports.ImportResourceSnapshot probeImportResources(Connection connection,
            String databaseName, String schemaName) {
        return ai.chat2db.spi.model.imports.ImportResourceSnapshot.unknown(
                "Presto is a federated query engine: sessions are short-lived queries over connectors "
                        + "rather than a bounded connection pool, so connection capacity is not a Presto "
                        + "concept; Presto has no replication; Presto has no row-level DML triggers, so "
                        + "trigger ordering cannot be a risk; and connector disk space is not exposed "
                        + "through Presto SQL");
    }
}
