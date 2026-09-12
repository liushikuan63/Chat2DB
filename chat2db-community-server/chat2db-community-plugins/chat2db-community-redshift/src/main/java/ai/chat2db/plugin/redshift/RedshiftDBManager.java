package ai.chat2db.plugin.redshift;

import ai.chat2db.plugin.postgresql.PostgreSQLDBManager;
import ai.chat2db.spi.IDbManager;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RedshiftDBManager extends PostgreSQLDBManager implements IDbManager {

    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.KEYSET_SHARDING;
    }
    /**
     * Consistent read for parallel export readers; the caller rolls the transaction back when the
     * worker finishes and falls back to auto-commit reads when this statement is unsupported.
     */
    @Override
    public boolean startConsistentExportSnapshot(java.sql.Connection connection) throws java.sql.SQLException {
        try (java.sql.PreparedStatement snapshot = connection.prepareStatement(SQL_EXPORT_SNAPSHOT)) {
            snapshot.execute();
            return true;
        }
    }

    private static final String SQL_EXPORT_SNAPSHOT = "BEGIN";

}
