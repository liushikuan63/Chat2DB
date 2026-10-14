package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import lombok.extern.slf4j.Slf4j;
import java.sql.Connection;
import java.sql.SQLException;

/** Receipt DDL/transactions use a fresh owned session, never a possibly dirty console pool lease. */
@Slf4j
public final class ImportReceiptConnectionScope implements AutoCloseable {
    private final ConnectInfo previous;
    private final ConnectInfo isolated;

    private ImportReceiptConnectionScope(ConnectInfo previous, ConnectInfo isolated) {
        this.previous = previous;
        this.isolated = isolated;
    }

    public static ImportReceiptConnectionScope open(ImportTaskSpec spec) {
        ConnectInfo previous = Chat2DBContext.getConnectInfo();
        if (!ImportRecoveryIdentity.DATABASE.equals(spec.getRecoveryProtocol()) || previous == null
                || previous.getUrl() == null || previous.getUrl().isBlank()) {
            return new ImportReceiptConnectionScope(previous, null);
        }
        ConnectInfo isolated = previous.copy();
        isolated.setLoginUser("import-recovery-" + spec.getRecoveryIdentity());
        Chat2DBContext.putContext(isolated);
        try {
            ConnectionPool.createNewConnection(isolated);
            return new ImportReceiptConnectionScope(previous, isolated);
        } catch (RuntimeException | Error failure) {
            Chat2DBContext.putContext(previous);
            throw failure;
        }
    }

    @Override public void close() {
        if (isolated == null) return;
        Connection connection = isolated.getConnection();
        isolated.setConnection(null);
        try {
            if (connection != null) connection.close();
        } catch (SQLException | RuntimeException failure) {
            log.warn("Could not close the dedicated import recovery connection", failure);
        } finally { Chat2DBContext.putContext(previous); }
    }
}
