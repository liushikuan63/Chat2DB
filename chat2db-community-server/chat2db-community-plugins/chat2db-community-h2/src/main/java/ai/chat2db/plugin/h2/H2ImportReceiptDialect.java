package ai.chat2db.plugin.h2;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.IImportReceiptDialect;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.plugin.h2.identifier.H2IdentifierProcessor;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class H2ImportReceiptDialect implements IImportReceiptDialect {
    @Override public Location location(Connection connection, ConnectInfo target) throws SQLException {
        String schema = target.getSchemaName();
        if (schema == null || schema.isBlank()) schema = connection.getSchema();
        String qualified = H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(schema) + "."
                + H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(TABLE_NAME);
        return new Location(connection.getCatalog(), schema, TABLE_NAME, qualified);
    }

    @Override public String createTableSql(Location location) {
        return "CREATE TABLE " + location.qualifiedName() + " ("
                + "run_key CHAR(64) NOT NULL, row_no BIGINT NOT NULL, payload_hash CHAR(64) NOT NULL, "
                + "outcome VARCHAR(16) NOT NULL, reason VARCHAR(1024), created_at BIGINT NOT NULL, "
                + "chat2db_owner_v1 INTEGER NOT NULL DEFAULT 1, "
                + "PRIMARY KEY (run_key, row_no))";
    }

    @Override public boolean isAlreadyExists(SQLException failure) {
        return failure.getErrorCode() == 42101;
    }

    @Override public void verifyTransactionalTarget(Connection connection, ConnectInfo target,
            Location location, String targetTable, ISqlExecutionStatementListener listener) throws SQLException {
        if (!supportsTransactionalTarget(connection, target, targetTable, listener)) {
            throw new SQLException("Import receipt recovery requires a native H2 table");
        }
    }

    @Override public boolean supportsTransactionalTarget(Connection connection, ConnectInfo target,
            String targetTable, ISqlExecutionStatementListener listener) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT TABLE_TYPE,STORAGE_TYPE FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            statement.setQueryTimeout(5); statement.setString(1, location(connection, target).schema());
            statement.setString(2, targetTable); listener.onStatementCreated(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && "BASE TABLE".equals(rows.getString(1))
                        && ("CACHED".equals(rows.getString(2)) || "MEMORY".equals(rows.getString(2)));
            } finally { listener.onStatementClosed(statement); }
        }
    }
}
