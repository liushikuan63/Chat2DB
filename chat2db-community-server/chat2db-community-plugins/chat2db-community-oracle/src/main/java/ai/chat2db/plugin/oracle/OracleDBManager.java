package ai.chat2db.plugin.oracle;

import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.FunctionMetadataRequest;
import ai.chat2db.spi.model.request.ProcedureMetadataRequest;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TriggerMetadataRequest;
import ai.chat2db.spi.model.request.ViewMetadataRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Date;
import java.util.List;

import static cn.hutool.core.date.DatePattern.NORM_DATETIME_PATTERN;

import static ai.chat2db.plugin.oracle.constant.OracleDBManagerConstants.*;
@Slf4j
public class OracleDBManager extends DefaultDBManager implements IDbManager {

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

    private static final String SQL_EXPORT_SNAPSHOT = "SET TRANSACTION READ ONLY";

    @Override
    public ai.chat2db.spi.model.imports.ImportResourceSnapshot probeImportResources(Connection connection,
            String databaseName, String schemaName) {
        int maxConnections = 0;
        int activeConnections = 0;
        boolean connectionCapacityKnown = false;
        StringBuilder evidence = new StringBuilder();
        try {
            maxConnections = queryInt(connection,
                    "SELECT TO_NUMBER(VALUE) FROM v$parameter WHERE NAME = 'sessions'");
            activeConnections = queryInt(connection,
                    "SELECT COUNT(*) FROM v$session");
            connectionCapacityKnown = maxConnections > 0;
        } catch (SQLException failure) {
            evidence.append("connection capacity unavailable (v$ views may need privileges); ");
        }

        boolean replicationStatusKnown = false;
        boolean replica = false;
        Long replicationLagSeconds = null;
        try {
            String role = queryString(connection,
                    "SELECT DATABASE_ROLE FROM v$database");
            replica = "PHYSICAL STANDBY".equalsIgnoreCase(StringUtils.trimToEmpty(role));
            if (replica) {
                replicationLagSeconds = queryNullableLong(
                        connection,
                        "SELECT GREATEST(0, CEIL((SYSDATE - VALUE) * 86400)) FROM v$dataguard_stats "
                                + "WHERE NAME = 'apply lag'");
            }
            replicationStatusKnown = true;
        } catch (SQLException failure) {
            evidence.append("replication status unavailable; ");
        }

        boolean triggerStatusKnown = false;
        int triggerCount = 0;
        try {
            String owner = resolveTriggerOwner(connection, schemaName);
            triggerCount = queryCount(connection, buildTriggerCountSql(owner));
            triggerStatusKnown = true;
        } catch (SQLException failure) {
            evidence.append("trigger metadata unavailable; ");
        }
        evidence.append("server disk free space is not exposed by Oracle SQL metadata");
        return new ai.chat2db.spi.model.imports.ImportResourceSnapshot(connectionCapacityKnown, maxConnections,
                activeConnections, replicationStatusKnown, replica, replicationLagSeconds, triggerStatusKnown,
                triggerCount, false, false, evidence.toString());
    }




    /** A blank owner means "the connected schema"; scanning every schema would overstate the risk. */
    String resolveTriggerOwner(Connection connection, String schemaName) throws SQLException {
        String owner = blankToNull(schemaName);
        if (owner == null) {
            owner = queryString(connection, "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual");
        }
        return owner;
    }

    static String buildTriggerCountSql(String owner) {
        return "SELECT COUNT(*) FROM all_triggers WHERE OWNER = '"
                + OracleIdentifierProcessor.INSTANCE.escapeString(owner) + "'";
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

    int queryCount(Connection connection, String sql) throws SQLException {
        return ai.chat2db.spi.model.imports.ImportResourceProbes.queryCount(connection, sql);
    }

    static String blankToNull(String value) {
        return ai.chat2db.spi.model.imports.ImportResourceProbes.blankToNull(value);
    }

    public void exportDatabase(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        context.write(String.format(EXPORT_TITLE, DateUtil.format(new Date(), NORM_DATETIME_PATTERN)));
        logDatabaseObjectExportStarted(context, "tables");
        exportTables(connection, databaseName, schemaName, containData, context);
        logDatabaseObjectExportCompleted(context, "tables");
        reportExportProgress(context, 50);
        logDatabaseObjectExportStarted(context, "views");
        exportViews(connection, context, schemaName);
        logDatabaseObjectExportCompleted(context, "views");
        reportExportProgress(context, 60);
        logDatabaseObjectExportStarted(context, "procedures");
        exportProcedures(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "procedures");
        reportExportProgress(context, 90);
        logDatabaseObjectExportStarted(context, "functions");
        exportFunctions(connection, schemaName, context);
        logDatabaseObjectExportCompleted(context, "functions");
    }

    private void exportTables(Connection connection, String databaseName, String schemaName, boolean containData,
            TaskExecutionContext context) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, schemaName, null, new String[]{"TABLE", "SYSTEM TABLE"})) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                exportTable(connection, databaseName, schemaName, tableName, containData, context);
            }
        }
    }


    public void exportTable(Connection connection, String databaseName, String schemaName, String tableName,
            boolean containData, TaskExecutionContext context) throws SQLException {
        String tableDDL = Chat2DBContext.getDbMetaData().tableDDL(connection,
                new TableMetadataRequest(databaseName, schemaName, tableName));
        String sqlBuilder = "DROP TABLE " + qualifiedName(schemaName, tableName) + ";\n" + tableDDL + "\n";
        context.write(sqlBuilder);
        if (containData) {
            exportTableData(connection, databaseName, schemaName, tableName, context);
        }

    }


    private void exportViews(Connection connection, TaskExecutionContext context, String schemaName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, schemaName, null, new String[]{"VIEW"})) {
            while (resultSet.next()) {
                String viewName = resultSet.getString("TABLE_NAME");
                exportView(connection, context, schemaName, viewName);
            }
        }
    }

    private void exportView(Connection connection, TaskExecutionContext context, String schemaName, String viewName) {
        Table view = Chat2DBContext.getDbMetaData().view(connection, new ViewMetadataRequest(null, schemaName, viewName));
        context.write(view.getDdl() + ";" + "\n");
    }

    private void exportProcedures(Connection connection, String schemaName, TaskExecutionContext context) {
        List<Procedure> procedures = Chat2DBContext.getDbMetaData().procedures(connection, null, schemaName);
        if (CollectionUtils.isNotEmpty(procedures)) {
            for (Procedure procedure : procedures) {
                String procedureName = procedure.getProcedureName();
                exportProcedure(connection, schemaName, procedureName, context);
            }
        }

    }

    private void exportProcedure(Connection connection, String schemaName, String procedureName, TaskExecutionContext context) {
        Procedure procedure = Chat2DBContext.getDbMetaData().procedure(connection,
                new ProcedureMetadataRequest(null, schemaName, procedureName));
        context.write(procedure.getProcedureBody() + "\n");

    }

    private void exportTriggers(Connection connection, String schemaName, TaskExecutionContext context) throws SQLException {
        String sql = String.format(SQL_SELECT_TRIGGER_NAME_ALL_TRIGGERS, OracleIdentifierProcessor.INSTANCE.escapeString(schemaName));
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String triggerName = resultSet.getString("TRIGGER_NAME");
                exportTrigger(connection, schemaName, triggerName, context);
            }
        }
    }

    private void exportTrigger(Connection connection, String schemaName, String triggerName, TaskExecutionContext context) {
        Trigger trigger = Chat2DBContext.getDbMetaData().trigger(connection,
                new TriggerMetadataRequest(null, schemaName, triggerName));
        context.write(trigger.getTriggerBody() + ";" + "\n");

    }

    private void exportFunctions(Connection connection, String schemaName, TaskExecutionContext context) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getFunctions(null, schemaName, null)) {
            while (resultSet.next()) {
                String functionName = resultSet.getString("FUNCTION_NAME");
                exportFunction(connection, schemaName, functionName, context);
            }
        }
    }

    private void exportFunction(Connection connection, String schemaName, String functionName, TaskExecutionContext context) {
        Function function = Chat2DBContext.getDbMetaData().function(connection,
                new FunctionMetadataRequest(null, schemaName, functionName));
        context.write(function.getFunctionBody() + "\n");
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
                    SQL_ALTER_SESSION_SET_CURRENT_SCHEMA
                            + OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName));
        } catch (SQLException e) {
            log.error("connectDatabase error", e);
        }
    }

    @Override
    public void copyTable(Connection connection, String databaseName, String schemaName, String tableName, String newTableName, boolean copyData) throws SQLException {
        String source = qualifiedName(schemaName, tableName);
        String target = qualifiedName(schemaName, newTableName);
        String sql;
        if (copyData) {
            sql = "CREATE TABLE " + target + " AS SELECT * FROM " + source;
        } else {
            sql = "CREATE TABLE " + target + " AS SELECT * FROM " + source + " WHERE 1=0";
        }
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

    @Override
    public String dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return "DROP TABLE " + qualifiedName(schemaName, tableName);
    }

    @Override
    public String truncateTable(Connection connection, String databaseName, String schemaName, String tableName) {
        return "TRUNCATE TABLE " + qualifiedName(schemaName, tableName);
    }

    @Override
    public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName, TaskExecutionContext context) {
        exportTableData(connection, databaseName, schemaName, tableName, context, 10000);
    }

    @Override
    public void dropView(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = "DROP VIEW " + qualifiedName(schemaName, viewName);
        DefaultSQLExecutor.getInstance().execute(connection, sql, (resultSet) -> null);
    }

    private static String qualifiedName(String schemaName, String objectName) {
        String quotedObject = OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(objectName);
        if (StringUtils.isBlank(schemaName)) {
            return quotedObject;
        }
        return OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName) + "." + quotedObject;
    }

}
