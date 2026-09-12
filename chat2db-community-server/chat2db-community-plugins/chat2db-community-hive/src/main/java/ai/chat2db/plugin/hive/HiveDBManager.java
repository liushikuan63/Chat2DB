package ai.chat2db.plugin.hive;

import java.sql.Connection;
import java.sql.SQLException;

import ai.chat2db.plugin.hive.identifier.HiveIdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.springframework.util.StringUtils;

import static ai.chat2db.plugin.hive.constant.HiveDBManagerConstants.*;
public class HiveDBManager extends DefaultDBManager implements IDbManager {

    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.KEYSET_SHARDING;
    }

    /**
     * Hive answers none of the admission resource questions, and the reasons are inherent to the
     * dialect rather than a failed probe: sessions are short-lived query sessions rather than a
     * bounded connection pool, so no connection-capacity view exists; Hive has no replication
     * concept; Hive has no row-level DML triggers, so trigger ordering cannot be a risk; and server
     * disk free space is not exposed through SQL. Reporting this explicitly keeps the admission
     * report honest instead of leaving the reader to guess.
     */
    @Override
    public ai.chat2db.spi.model.imports.ImportResourceSnapshot probeImportResources(Connection connection,
            String databaseName, String schemaName) {
        return ai.chat2db.spi.model.imports.ImportResourceSnapshot.unknown(
                "Hive is an MPP query engine: sessions are short-lived queries rather than a bounded "
                        + "connection pool, so connection capacity is not a Hive concept; Hive has no "
                        + "replication; Hive has no row-level DML triggers, so trigger ordering cannot "
                        + "be a risk; and server disk free space is not exposed through Hive SQL");
    }





    @Override
    public void connectDatabase(Connection connection, String database) {
        if (StringUtils.isEmpty(database)) {
            return;
        }
        try {
            DefaultSQLExecutor.getInstance().execute(connection, String.format(SQL_USE_DATABASE, HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database)));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_DROP_TABLE_EXISTS, HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName,boolean copyData) throws SQLException {
        String sql = String.format(SQL_COPY_TABLE, HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTableName), HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
        if(copyData){
            sql = String.format(SQL_INSERT_TABLE_SELECT, HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTableName), HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
            DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
        }
    }
}
