package ai.chat2db.plugin.postgresql;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.IImportReceiptDialect;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class PostgreSQLImportReceiptDialect implements IImportReceiptDialect {
    @Override public Location location(Connection connection, ConnectInfo target) throws SQLException {
        String schema = target.getSchemaName();
        if (schema == null || schema.isBlank()) schema = connection.getSchema();
        if (schema == null || schema.isBlank()) throw new SQLException("An import receipt schema is required");
        String qualified = PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schema) + "."
                + PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(TABLE_NAME);
        return new Location(connection.getCatalog(), schema, TABLE_NAME, qualified);
    }

    @Override public String createTableSql(Location location) {
        return "CREATE TABLE " + location.qualifiedName() + " ("
                + "run_key CHAR(64) NOT NULL, row_no BIGINT NOT NULL, payload_hash CHAR(64) NOT NULL, "
                + "outcome VARCHAR(16) NOT NULL, reason VARCHAR(1024), created_at BIGINT NOT NULL, "
                + "chat2db_owner_v1 INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (run_key, row_no))";
    }

    @Override public boolean isAlreadyExists(SQLException failure) { return "42P07".equals(failure.getSQLState()); }

    @Override public void verifyTransactionalTarget(Connection connection, ConnectInfo target, Location location,
            String targetTable, ISqlExecutionStatementListener listener) throws SQLException {
        // Transaction-scoped ACCESS SHARE locks prevent replacement/unlogging between the
        // durability check and row commit. No target DML occurs before both checks pass.
        for (String qualified : new String[]{location.qualifiedName(),
                PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(location.schema()) + "."
                        + PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(targetTable)}) {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5); listener.onStatementCreated(statement);
                try (var rows = statement.executeQuery("SELECT 1 FROM " + qualified + " LIMIT 0")) { }
                finally { listener.onStatementClosed(statement); }
            }
        }
        if (!supportsTransactionalTarget(connection, target, targetTable, listener)
                || !supportsTransactionalTarget(connection, target, location.table(), listener)) {
            throw new SQLException("Import receipt recovery requires logged permanent PostgreSQL heap tables");
        }
    }

    @Override public boolean supportsTransactionalTarget(Connection connection, ConnectInfo target,
            String targetTable, ISqlExecutionStatementListener listener) throws SQLException {
        // UNLOGGED tables are truncated after database crash while logged receipts survive.
        // Partitioned parents need a separate leaf capability proof; ordinary imports stay available.
        try (PreparedStatement statement = connection.prepareStatement("SELECT c.relkind,c.relpersistence FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?")) {
            statement.setQueryTimeout(5); statement.setString(1, location(connection, target).schema()); statement.setString(2, targetTable);
            listener.onStatementCreated(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && "r".equals(rows.getString(1)) && "p".equals(rows.getString(2));
            } finally { listener.onStatementClosed(statement); }
        }
    }
}
