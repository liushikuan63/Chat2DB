package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.IImportReceiptDialect;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class MysqlImportReceiptDialect implements IImportReceiptDialect {
    @Override public Location location(Connection connection, ConnectInfo target) throws SQLException {
        String database = target.getDatabaseName();
        if (database == null || database.isBlank()) database = connection.getCatalog();
        if (database == null || database.isBlank()) throw new SQLException("An import receipt database is required");
        String qualified = quote(database) + "." + quote(TABLE_NAME);
        return new Location(database, null, TABLE_NAME, qualified);
    }

    private static String quote(String name) { return MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(name); }

    @Override public String createTableSql(Location location) {
        return "CREATE TABLE " + location.qualifiedName() + " ("
                + "run_key CHAR(64) NOT NULL, row_no BIGINT NOT NULL, payload_hash CHAR(64) NOT NULL, "
                + "outcome VARCHAR(16) NOT NULL, reason VARCHAR(1024), created_at BIGINT NOT NULL, "
                + "chat2db_owner_v1 INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (run_key, row_no)) ENGINE=InnoDB";
    }

    @Override public boolean isAlreadyExists(SQLException failure) { return failure.getErrorCode() == 1050; }

    @Override public boolean supportsTransactionalTarget(Connection connection, ConnectInfo target, String targetTable,
            ISqlExecutionStatementListener listener) throws SQLException {
        String database = location(connection, target).catalog();
        try (PreparedStatement statement = connection.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            statement.setQueryTimeout(5); statement.setString(1, database); statement.setString(2, targetTable);
            listener.onStatementCreated(statement);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() && "InnoDB".equalsIgnoreCase(rows.getString(1)); }
            finally { listener.onStatementClosed(statement); }
        }
    }

    @Override public void verifyTransactionalTarget(Connection connection, ConnectInfo target, Location location,
            String targetTable, ISqlExecutionStatementListener listener) throws SQLException {
        // In a row transaction these references hold metadata locks until commit/rollback, so
        // another connection cannot switch either table's engine after the following check.
        for (String table : new String[]{location.qualifiedName(), quote(location.catalog()) + "." + quote(targetTable)}) {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5); listener.onStatementCreated(statement);
                try (ResultSet ignored = statement.executeQuery("SELECT 1 FROM " + table + " LIMIT 0")) { }
                finally { listener.onStatementClosed(statement); }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME IN (?,?)")) {
            statement.setQueryTimeout(5); statement.setString(1, location.catalog());
            statement.setString(2, location.table()); statement.setString(3, targetTable);
            listener.onStatementCreated(statement);
            int found = 0;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!"InnoDB".equalsIgnoreCase(rows.getString("ENGINE"))) {
                        throw new SQLException("Target and import receipts must both use InnoDB");
                    }
                    found++;
                }
                if (found != 2) throw new SQLException("The transactional import target or receipt table is missing");
            } finally { listener.onStatementClosed(statement); }
        }
    }
}
