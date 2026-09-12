package ai.chat2db.plugin.snowflake;

import ai.chat2db.plugin.snowflake.identifier.SnowflakeIdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static ai.chat2db.plugin.snowflake.constant.SnowflakeDBManagerConstants.*;
public class SnowflakeDBManager extends DefaultDBManager implements IDbManager {

    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.KEYSET_SHARDING;
    }

    /**
     * Snowflake's unknowns are deliberate. Its account-usage warehouse load view is documented to lag
     * by up to three hours and to aggregate into five-minute intervals, so it cannot answer whether
     * there is headroom right now; answering "known" from stale data would be worse than admitting
     * the unknown. Snowflake also has no replication-lag view usable for this decision, no row-level
     * DML triggers, and no SQL-exposed server disk free space.
     */
    @Override
    public ai.chat2db.spi.model.imports.ImportResourceSnapshot probeImportResources(Connection connection,
            String databaseName, String schemaName) {
        return ai.chat2db.spi.model.imports.ImportResourceSnapshot.unknown(
                "Snowflake resource views cannot answer a pre-import admission decision: the "
                        + "account-usage warehouse load view lags by up to 3 hours and aggregates into "
                        + "5-minute intervals, so it cannot report current headroom; there is no "
                        + "replication-lag view usable for this decision; Snowflake has no row-level "
                        + "DML triggers; and server disk free space is not exposed through Snowflake SQL");
    }





    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        List<KeyValue> extendInfo = connectInfo.getExtendInfo();
        if (StringUtils.isNotBlank(connectInfo.getDatabaseName())) {
            KeyValue keyValue = new KeyValue();
            keyValue.setKey("db");
            keyValue.setValue(connectInfo.getDatabaseName());
            extendInfo.add(keyValue);
        }
        if (StringUtils.isNotBlank(connectInfo.getSchemaName())) {
            KeyValue keyValue = new KeyValue();
            keyValue.setKey("schema");
            keyValue.setValue(connectInfo.getSchemaName());
            extendInfo.add(keyValue);
        }
        KeyValue keyValue = new KeyValue();
        keyValue.setKey("JDBC_QUERY_RESULT_FORMAT");
        keyValue.setValue("JSON");
        extendInfo.add(keyValue);
        connectInfo.setExtendInfo(extendInfo);
        return super.getConnection(connectInfo);
    }


    @Override
    public void connectDatabase(Connection connection, String database) {
        if (StringUtils.isEmpty(database)) {
            return;
        }
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (ObjectUtils.anyNull(connectInfo) || StringUtils.isEmpty(connectInfo.getSchemaName())) {
            try {
                DefaultSQLExecutor.getInstance().execute(connection,
                        String.format(SQL_USE_DATABASE, SnowflakeIdentifierProcessor.escapeIdentifier(database)));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        } else {
            try {
                DefaultSQLExecutor.getInstance().execute(connection,
                        String.format(SQL_USE_SCHEMA, SnowflakeIdentifierProcessor.escapeIdentifier(database),
                                SnowflakeSqlGuards.requireSnowflakeName(connectInfo.getSchemaName(), "schema name")));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_DROP_TABLE, format(tableName));
    }

    public static String format(String tableName) {
        return SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }

}
