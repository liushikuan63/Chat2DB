package ai.chat2db.spi;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import java.sql.Connection;
import java.sql.SQLException;

/** Optional transactional row-import receipt support, implemented by the owning database plugin. */
public interface IImportReceiptDialect {
    String TABLE_NAME = "__chat2db_import_receipts_v1";

    record Location(String catalog, String schema, String table, String qualifiedName) { }

    Location location(Connection connection, ConnectInfo target) throws SQLException;

    /** Creates a new table only. Existing tables must be validated, never silently adopted. */
    String createTableSql(Location location);

    boolean isAlreadyExists(SQLException failure);

    default boolean supportsTransactionalTarget(Connection connection, ConnectInfo target, String targetTable,
            ISqlExecutionStatementListener listener) throws SQLException {
        return connection.getMetaData().supportsTransactions();
    }

    /** Rejects target/receipt storage whose writes cannot share an atomic transaction. */
    void verifyTransactionalTarget(Connection connection, ConnectInfo target, Location location,
            String targetTable, ISqlExecutionStatementListener listener) throws SQLException;
}
