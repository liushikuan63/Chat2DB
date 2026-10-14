package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.plugin.h2.H2ImportReceiptDialect;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Database state is the oracle: the target deliberately has no unique key. */
class ImportReceiptStoreTest {
    Connection keeper;
    ConnectInfo info;
    String url;
    String runKey;
    Context context;

    @BeforeEach void setUp() throws Exception {
        url = "jdbc:h2:mem:receipts_" + UUID.randomUUID().toString().replace("-", "");
        runKey = UUID.randomUUID().toString().replace("-", "") + "a".repeat(32);
        keeper = DriverManager.getConnection(url);
        try (Statement statement = keeper.createStatement()) {
            statement.execute("CREATE TABLE TARGET_ROWS (ID INT, NOTE VARCHAR(100))");
        }
        info = new ConnectInfo(); info.setDbType("H2"); info.setSchemaName("PUBLIC");
        info.setDriverConfig(new DriverConfig());
        info.setConnection(DriverManager.getConnection(url));
        Chat2DBContext.putContext(info);
        context = new Context();
    }

    @AfterEach void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (info.getConnection() != null) info.getConnection().close();
        keeper.close();
    }

    ImportReceiptStore open(String fingerprint) throws Exception {
        return ImportReceiptStore.open(new H2ImportReceiptDialect(), info, "TARGET_ROWS", runKey, fingerprint, context);
    }

    @Test void reopenSkipsCommittedRowsEvenWithoutPrimaryKeysOrLocalCheckpoints() throws Exception {
        ImportReceiptStore first = open("a".repeat(64));
        assertTrue(first.execute(1, "INSERT INTO TARGET_ROWS VALUES (1, 'one')"));
        info.getConnection().close(); info.setConnection(DriverManager.getConnection(url));
        ImportReceiptStore resumed = open("a".repeat(64));
        assertFalse(resumed.execute(1, "INSERT INTO TARGET_ROWS VALUES (1, 'one')"));
        assertTrue(resumed.execute(2, "INSERT INTO TARGET_ROWS VALUES (2, 'two')"));
        assertEquals(2, count("TARGET_ROWS"));
        assertEquals(2, countReceipts());
    }

    @Test void changedSourceOrRowCannotReuseAnExistingReceipt() throws Exception {
        ImportReceiptStore store = open("a".repeat(64));
        store.execute(1, "INSERT INTO TARGET_ROWS VALUES (1, 'one')");
        assertThrows(RuntimeException.class, () -> open("b".repeat(64)));
        assertThrows(RuntimeException.class, () -> store.execute(1, "INSERT INTO TARGET_ROWS VALUES (1, 'changed')"));
        assertEquals(1, count("TARGET_ROWS"));
    }

    @Test void failedRowRollsBackItsReceiptAndKeepsTheSuccessfulPrefix() throws Exception {
        ImportReceiptStore store = open("a".repeat(64));
        store.execute(1, "INSERT INTO TARGET_ROWS VALUES (1, 'one')");
        assertThrows(RuntimeException.class, () -> store.execute(2, "INSERT INTO TARGET_ROWS VALUES ('bad-int', 'two')"));
        assertEquals(1, count("TARGET_ROWS"));
        assertEquals(1, countReceipts());
        assertTrue(info.getConnection().getAutoCommit());
    }

    @Test void cancellationAfterTargetExecutionRollsBackBothTargetAndReceipt() throws Exception {
        ImportReceiptStore store = open("a".repeat(64));
        context.cancelAfterStatement = true;
        assertThrows(TaskCancelledException.class, () -> store.execute(1, "INSERT INTO TARGET_ROWS VALUES (1, 'one')"));
        assertTrue(context.sawUncommittedTargetRow, "cancellation must occur after real target DML");
        assertEquals(0, count("TARGET_ROWS"));
        assertEquals(0, countReceipts());
    }

    @Test void foreignTableNameConflictIsRejectedWithoutAdoptingOrChangingIt() throws Exception {
        try (Statement statement = keeper.createStatement()) {
            statement.execute("CREATE TABLE PUBLIC.\"__chat2db_import_receipts_v1\" (USER_DATA VARCHAR(50))");
            statement.execute("INSERT INTO PUBLIC.\"__chat2db_import_receipts_v1\" VALUES ('keep-me')");
        }
        assertThrows(RuntimeException.class, () -> open("a".repeat(64)));
        try (Statement statement = keeper.createStatement();
             ResultSet rows = statement.executeQuery("SELECT USER_DATA FROM PUBLIC.\"__chat2db_import_receipts_v1\"")) {
            assertTrue(rows.next()); assertEquals("keep-me", rows.getString(1)); assertFalse(rows.next());
        }
        assertEquals(0, count("TARGET_ROWS"));
    }

    @Test void callerTransactionIsNeverCommittedOrChangedDuringReceiptAdmission() throws Exception {
        info.getConnection().setAutoCommit(false);
        assertThrows(RuntimeException.class, () -> open("a".repeat(64)));
        assertFalse(info.getConnection().getAutoCommit());
        assertFalse(info.getConnection().isClosed());
        assertEquals(0, count("TARGET_ROWS"));
    }

    @Test void lostCommitResponseReconcilesFromTheReceiptWithoutReinserting() throws Exception {
        lostCommitResponse(false);
    }

    @Test void runtimeFailureAfterCommitAlsoPreservesTheUnknownOutcome() throws Exception {
        lostCommitResponse(true);
    }

    private void lostCommitResponse(boolean runtimeFailure) throws Exception {
        ImportReceiptStore store = open("a".repeat(64));
        Connection physical = info.getConnection();
        info.setConnection((Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(physical, args);
                        if (method.getName().equals("commit")) {
                            if (runtimeFailure) throw new IllegalStateException("commit response lost");
                            throw new SQLException("commit response lost", "08006");
                        }
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                }));
        ImportReceiptStore.ReceiptSafetyException unknown = assertThrows(ImportReceiptStore.ReceiptSafetyException.class,
                () -> store.execute(1, "INSERT INTO TARGET_ROWS VALUES (1, 'one')"));
        assertEquals("IMPORT_COMMIT_OUTCOME_UNKNOWN", unknown.getCode());
        assertNull(info.getConnection()); assertTrue(physical.isClosed());
        assertEquals(1, count("TARGET_ROWS")); assertEquals(1, countReceipts());
        info.setConnection(DriverManager.getConnection(url));
        assertFalse(open("a".repeat(64)).execute(1, "INSERT INTO TARGET_ROWS VALUES (1, 'one')"));
        assertEquals(1, count("TARGET_ROWS"));
    }

    @Test void concurrentReceiptClaimsProduceOneTargetWrite() throws Exception {
        ImportReceiptStore store = open("a".repeat(64));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> action = () -> {
                ConnectInfo worker = new ConnectInfo(); worker.setDbType("H2");
                worker.setDriverConfig(new DriverConfig()); worker.setConnection(DriverManager.getConnection(url));
                Chat2DBContext.putContext(worker);
                try {
                    ready.countDown(); start.await();
                    return store.execute(1, "INSERT INTO TARGET_ROWS VALUES (1, 'one')");
                } finally {
                    Chat2DBContext.removeContext();
                    if (worker.getConnection() != null) worker.getConnection().close();
                }
            };
            var first = workers.submit(action); var second = workers.submit(action);
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            assertNotEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, count("TARGET_ROWS")); assertEquals(1, countReceipts());
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    long count(String table) throws Exception {
        try (Statement statement = keeper.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    long countReceipts() throws Exception {
        try (var statement = keeper.prepareStatement("SELECT COUNT(*) FROM PUBLIC.\"__chat2db_import_receipts_v1\" WHERE run_key=? AND row_no>0")) {
            statement.setString(1, runKey);
            try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    static class Context implements TaskExecutionContext {
        boolean cancelled; boolean cancelAfterStatement; boolean sawUncommittedTargetRow;
        public void reportProgress(int progress, String stage, String message) { }
        public void logInfo(String code, String message) { }
        public void logInfo(String code, String message, Map<String,Object> details) { }
        public void logWarn(String code, String message, Map<String,Object> details) { }
        public void logError(String code, String message, Map<String,Object> details) { }
        public void checkCancelled() { if (cancelled) throw new TaskCancelledException(); }
        public void registerCancelable(TaskCancelable resource) { }
        public ArtifactDraft createArtifact(String path, String name, String mediaType) { throw new UnsupportedOperationException(); }
        public void write(String content) { throw new UnsupportedOperationException(); }
        public void onStatementCreated(Statement statement) { }
        public void onStatementClosed(Statement statement) {
            if (!cancelAfterStatement) return;
            try (Statement observer = Chat2DBContext.getConnectInfo().getConnection().createStatement();
                 ResultSet rows = observer.executeQuery("SELECT COUNT(*) FROM TARGET_ROWS")) {
                rows.next();
                if (rows.getLong(1) > 0) { sawUncommittedTargetRow = true; cancelled = true; }
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
        }
    }
}
