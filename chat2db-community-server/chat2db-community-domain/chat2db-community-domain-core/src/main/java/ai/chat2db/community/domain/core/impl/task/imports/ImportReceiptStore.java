package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.IImportReceiptDialect;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import ai.chat2db.spi.DefaultSQLExecutor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Atomic database receipts for task-owned, standard single-table row imports. */
public final class ImportReceiptStore {
    private final IImportReceiptDialect dialect;
    private final ConnectInfo target;
    private final String targetTable;
    private final IImportReceiptDialect.Location location;
    private final String runKey;
    private final TaskExecutionContext context;

    private ImportReceiptStore(IImportReceiptDialect dialect, ConnectInfo target, String targetTable,
            IImportReceiptDialect.Location location, String runKey,
            TaskExecutionContext context) {
        this.dialect = dialect;
        this.target = target.copy();
        this.targetTable = targetTable;
        this.location = location;
        this.runKey = runKey;
        this.context = context;
    }

    public static ImportReceiptStore open(IImportReceiptDialect dialect, ConnectInfo target,
            String targetTable, String runKey, String sourceFingerprint, TaskExecutionContext context) {
        if (runKey == null || !runKey.matches("[0-9a-f]{64}")
                || sourceFingerprint == null || !sourceFingerprint.matches("[0-9a-f]{64}")) {
            throw unsafe("Invalid import receipt identity", null);
        }
        // Raw JDBC is used only for fixed, parameterized internal receipt operations. Target DML
        // still uses the task's statement-guarded connection and cancellation listener.
        Connection connection = ConnectionPool.getConnection(target);
        try {
            if (!connection.getAutoCommit()) {
                throw unsafe("Import receipt admission cannot alter a caller-owned transaction", null);
            }
            IImportReceiptDialect.Location location = dialect.location(connection, target);
            if (location.table().equalsIgnoreCase(targetTable)) {
                throw unsafe("The owned import receipt table cannot be an import target", null);
            }
            synchronized (ImportReceiptStore.class) {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(5);
                    context.onStatementCreated(statement);
                    try {
                        context.checkCancelled();
                        statement.execute(dialect.createTableSql(location));
                    } catch (SQLException failure) {
                        if (!dialect.isAlreadyExists(failure)) throw failure;
                    } finally {
                        context.onStatementClosed(statement);
                    }
                }
                validateSchema(connection, location);
                dialect.verifyTransactionalTarget(connection, target, location, targetTable, context);
                ImportReceiptStore store = new ImportReceiptStore(dialect, target, targetTable, location, runKey, context);
                Receipt header = store.read(connection, 0);
                if (header == null) {
                    try {
                        store.insert(connection, 0, sourceFingerprint, "SOURCE", null);
                    } catch (SQLException race) {
                        if (!isUniqueViolation(race)) throw race;
                    }
                    header = store.read(connection, 0);
                }
                if (header == null || !sourceFingerprint.equals(header.hash())
                        || !"SOURCE".equals(header.outcome())) {
                    throw unsafe("Import source or target changed since its database receipt was created", null);
                }
                return store;
            }
        } catch (SQLException failure) {
            discard(connection, failure);
            throw unsafe("Could not admit the owned import receipt table", failure);
        }
    }

    /** Claims the unique receipt before DML; a concurrent/replayed claim cannot write twice. */
    public boolean execute(long sourceRow, String sql) {
        if (sourceRow < 1 || sql == null || sql.isBlank()) throw unsafe("Invalid import row receipt", null);
        return write(sourceRow, hash(sql), "IMPORTED", null, sql);
    }

    public void recordRejected(long sourceRow, String identity, String reason) {
        write(sourceRow, hash(identity), "REJECTED", reason == null ? "Rejected row"
                : reason.substring(0, Math.min(reason.length(), 1024)), null);
    }

    private boolean write(long sourceRow, String rowHash, String outcome, String reason, String sql) {
        Connection connection = ConnectionPool.getConnection(Chat2DBContext.getConnectInfo());
        boolean transactionStarted = false;
        boolean commitAttempted = false;
        boolean commitPhase = false;
        try {
            context.checkCancelled();
            if (!connection.getAutoCommit()) throw unsafe("Receipt execution requires a task-owned transaction", null);
            connection.setAutoCommit(false);
            transactionStarted = true;
            try { dialect.verifyTransactionalTarget(connection, target, location, targetTable, context); }
            catch (SQLException failure) { throw unsafe("The target no longer supports atomic import receipts", failure); }
            try {
                insert(connection, sourceRow, rowHash, outcome, reason);
            } catch (SQLException duplicate) {
                if (!isUniqueViolation(duplicate)) throw duplicate;
                // PostgreSQL marks a failed transaction aborted; read only after rollback.
                connection.rollback();
                connection.setAutoCommit(true);
                transactionStarted = false;
                Receipt prior = read(connection, sourceRow);
                if (prior == null || !rowHash.equals(prior.hash())) {
                    throw unsafe("Import row conflicts with its previously committed receipt", duplicate);
                }
                if ("IMPORTED".equals(outcome) && "REJECTED".equals(prior.outcome())) {
                    throw new RejectedReceiptException(prior.reason());
                }
                if (!outcome.equals(prior.outcome())) throw unsafe("Import receipt outcome conflicts", duplicate);
                return false;
            }
            if (sql != null) DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), sql, context, context::checkCancelled);
            context.checkCancelled();
            context.enterCommitPhase();
            commitPhase = true;
            commitAttempted = true;
            connection.commit();
            transactionStarted = false;
            try {
                connection.setAutoCommit(true);
            } catch (SQLException | RuntimeException restoreFailure) {
                discard(connection, restoreFailure);
                // The row and receipt are committed. A cleanup failure cannot turn it into a replay.
            }
            return true;
        } catch (SQLException failure) {
            if (commitAttempted) {
                discard(connection, failure);
                throw new ReceiptSafetyException("IMPORT_COMMIT_OUTCOME_UNKNOWN",
                        "Import commit outcome is unknown; recover this task from its database receipts", failure);
            }
            if (transactionStarted) rollbackAndRestore(connection, failure);
            throw ImportSqlExecutor.importFailure(failure);
        } catch (RuntimeException | Error failure) {
            if (commitAttempted) {
                discard(connection, failure);
                throw new ReceiptSafetyException("IMPORT_COMMIT_OUTCOME_UNKNOWN",
                        "Import commit outcome is unknown; recover this task from its database receipts", failure);
            }
            if (transactionStarted) rollbackAndRestore(connection, failure);
            throw failure;
        } finally {
            if (commitPhase) context.exitCommitPhase();
        }
    }

    private Receipt read(Connection connection, long row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT payload_hash,outcome,reason FROM "
                + location.qualifiedName() + " WHERE run_key=? AND row_no=?")) {
            statement.setQueryTimeout(5); statement.setString(1, runKey); statement.setLong(2, row);
            context.onStatementCreated(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new Receipt(rows.getString(1), rows.getString(2), rows.getString(3)) : null;
            } finally { context.onStatementClosed(statement); }
        }
    }

    private void insert(Connection connection, long row, String hash, String outcome, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + location.qualifiedName()
                + " (run_key,row_no,payload_hash,outcome,reason,created_at) VALUES (?,?,?,?,?,?)")) {
            statement.setQueryTimeout(5); statement.setString(1, runKey); statement.setLong(2, row);
            statement.setString(3, hash); statement.setString(4, outcome); statement.setString(5, reason);
            statement.setLong(6, System.currentTimeMillis());
            context.onStatementCreated(statement);
            try { context.checkCancelled(); statement.executeUpdate(); }
            finally { context.onStatementClosed(statement); }
        }
    }

    private static void validateSchema(Connection connection, IImportReceiptDialect.Location location) throws SQLException {
        String escape = connection.getMetaData().getSearchStringEscape();
        String schemaPattern = literalMetadataPattern(location.schema(), escape);
        String pattern = literalMetadataPattern(location.table(), escape);
        List<String> names = new ArrayList<>();
        int[] types = {Types.CHAR, Types.BIGINT, Types.CHAR, Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.INTEGER};
        String[] columns = {"run_key", "row_no", "payload_hash", "outcome", "reason", "created_at", "chat2db_owner_v1"};
        try (ResultSet rows = connection.getMetaData().getColumns(location.catalog(), schemaPattern, pattern, "%")) {
            while (rows.next()) {
                int index = names.size();
                String name = rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                if (index >= columns.length || !columns[index].equals(name) || rows.getInt("DATA_TYPE") != types[index]
                        || rows.getInt("NULLABLE") != (index == 4 ? 1 : 0)
                        || index == 6 && !"1".equals(rows.getString("COLUMN_DEF"))) {
                    throw unsafe("Existing receipt table is not owned by the supported schema version", null);
                }
                names.add(name);
            }
        }
        if (names.size() != columns.length) throw unsafe("Existing receipt table has an incompatible owned schema", null);
        List<String> keys = new ArrayList<>();
        try (ResultSet rows = connection.getMetaData().getPrimaryKeys(location.catalog(), location.schema(), location.table())) {
            while (rows.next()) keys.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if (keys.size() != 2 || !keys.containsAll(List.of("run_key", "row_no"))) {
            throw unsafe("Owned receipt table primary key is incompatible", null);
        }
    }

    private static String literalMetadataPattern(String name, String escape) {
        return name == null ? null : name.replace(escape, escape + escape)
                .replace("_", escape + "_").replace("%", escape + "%");
    }

    private static boolean isUniqueViolation(SQLException failure) {
        return failure.getSQLState() != null && failure.getSQLState().startsWith("23");
    }

    private static void rollbackAndRestore(Connection connection, Throwable failure) {
        try { connection.rollback(); }
        catch (SQLException | RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure); discard(connection, failure); return;
        }
        try { connection.setAutoCommit(true); }
        catch (SQLException | RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); discard(connection, failure); }
    }

    private static void discard(Connection connection, Throwable failure) {
        ConnectInfo current = Chat2DBContext.getConnectInfo();
        if (current != null) current.setConnection(null);
        try { connection.close(); }
        catch (SQLException | RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is unavailable", failure); }
    }

    private static ReceiptSafetyException unsafe(String message, Throwable failure) {
        return new ReceiptSafetyException("IMPORT_RECEIPT_UNSAFE", message, failure);
    }

    private record Receipt(String hash, String outcome, String reason) { }

    public static final class ReceiptSafetyException extends TaskExecutionException {
        ReceiptSafetyException(String code, String message, Throwable failure) { super(code, message, failure); }
    }

    public static final class RejectedReceiptException extends RuntimeException {
        RejectedReceiptException(String reason) { super(reason); }
    }
}
