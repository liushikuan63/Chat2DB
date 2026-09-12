package ai.chat2db.plugin.h2;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.plugin.h2.identifier.H2IdentifierProcessor;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;

import static ai.chat2db.plugin.h2.constant.H2DBManagerConstants.*;
@Slf4j
public class H2DBManager extends DefaultDBManager implements IDbManager {

    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.KEYSET_SHARDING;
    }

    @Override
    public ai.chat2db.spi.model.imports.ImportResourceSnapshot probeImportResources(Connection connection,
            String databaseName, String schemaName) {
        boolean triggerStatusKnown = false;
        int triggerCount = 0;
        StringBuilder evidence = new StringBuilder();
        evidence.append("H2 is embedded, so connection capacity and replication status do not apply; ");
        try {
            triggerCount = queryTriggerCount(connection, schemaName);
            triggerStatusKnown = true;
        } catch (SQLException failure) {
            evidence.append("trigger metadata unavailable; ");
        }
        evidence.append("server disk free space is not exposed by H2 SQL metadata");
        return new ai.chat2db.spi.model.imports.ImportResourceSnapshot(false, 0, 0,
                false, false, null, triggerStatusKnown, triggerCount, false, false, evidence.toString());
    }

    int queryTriggerCount(Connection connection, String schemaName) throws SQLException {
        return queryCount(connection,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = "
                        + "COALESCE(" + sqlLiteral(schemaName) + ", SCHEMA())");
    }

    int queryCount(Connection connection, String sql) throws SQLException {
        return ai.chat2db.spi.model.imports.ImportResourceProbes.queryCount(connection, sql);
    }

    int queryInt(Connection connection, String sql) throws SQLException {
        return ai.chat2db.spi.model.imports.ImportResourceProbes.queryInt(connection, sql);
    }

    String queryString(Connection connection, String sql) throws SQLException {
        return ai.chat2db.spi.model.imports.ImportResourceProbes.queryString(connection, sql);
    }

    Long queryNullableLong(Connection connection, String sql) throws SQLException {
        return ai.chat2db.spi.model.imports.ImportResourceProbes.queryNullableLong(connection, sql);
    }

    private static String sqlLiteral(String value) {
        if (StringUtils.isBlank(value)) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }


    @Override
    public void exportDatabase(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        exportSchema(connection, schemaName, containData, context);
    }

    private void exportSchema(Connection connection, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        String template = "SCRIPT NODATA NOPASSWORDS NOSETTINGS DROP SCHEMA %s;";
        if (containData) {
            template = template.replace("NODATA", "");
        }
        String sql = String.format(template, H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String script = resultSet.getString("SCRIPT");
                if (!(script.startsWith("CREATE USER")||script.startsWith("--"))) {
                    StringBuilder sqlBuilder = new StringBuilder();
                    sqlBuilder.append(script);
                    sqlBuilder.append("\n");
                    context.write(sqlBuilder.toString());
                }
            }
        }

    }

    @Override
    public void connectDatabase(Connection connection, String database) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (ObjectUtils.anyNull(connectInfo) || StringUtils.isEmpty(connectInfo.getSchemaName())) {
            return;
        }
        String schemaName = connectInfo.getSchemaName();
        try {
            DefaultSQLExecutor.getInstance().execute(connection,
                String.format(SQL_SET_SCHEMA, H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName)));
        } catch (SQLException e) {
            log.error("Failed to set schema: {}", schemaName, e);
        }
    }


    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return String.format(SQL_DROP_TABLE, H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName));
    }
}
