package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring coverage for the multi-row INSERT fast path inside
 * {@link DefaultSQLExecutor#executeBatchInsert}. {@link ai.chat2db.spi.util.MultiRowInsertSql} is
 * unit-tested on its own; this test proves the executor actually takes the merged path for a
 * capable dialect, and that the merged statement is what the server received (rather than the
 * plain one-statement-per-row batch).
 */
class DefaultSQLExecutorMultiRowInsertTest {

    private static final String TEST_DB_TYPE = "H2";

    private IPlugin previousPlugin;

    @org.junit.jupiter.api.BeforeEach
    void registerPlugin() {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new TestPlugin());
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        System.clearProperty(ai.chat2db.spi.util.MultiRowInsertSql.ENABLED_PROPERTY);
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void mergesConsecutiveSingleRowInsertsIntoOneStatement() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:multi_row_wiring;DB_CLOSE_DELAY=-1")) {
            putContext(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE MR_ROWS (ID INT PRIMARY KEY, LABEL VARCHAR(32))");
            }

            List<String> sqls = new ArrayList<>();
            for (int id = 1; id <= 6; id++) {
                sqls.add("INSERT INTO MR_ROWS (ID, LABEL) VALUES (" + id + ", 'r" + id + "')");
            }
            List<String> batched = new ArrayList<>();
            new DefaultSQLExecutor().executeBatchInsert(recording(connection, batched), sqls, null, null, 0);

            assertEquals(6, count(connection, "SELECT COUNT(*) FROM MR_ROWS"),
                    "every row must land exactly once");
            assertEquals(1, batched.size(),
                    "six single-row inserts on a capable dialect must collapse into one statement, saw "
                            + batched);
            assertTrue(batched.get(0).contains("),"),
                    "the merged statement must carry multiple row tuples, saw " + batched.get(0));
        }
    }

    @Test
    void keepsTheLegacyPathWhenTheOptimizationIsDisabled() throws Exception {
        System.setProperty(ai.chat2db.spi.util.MultiRowInsertSql.ENABLED_PROPERTY, "false");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:multi_row_disabled;DB_CLOSE_DELAY=-1")) {
            putContext(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE MR_OFF (ID INT PRIMARY KEY)");
            }

            List<String> sqls = new ArrayList<>();
            for (int id = 1; id <= 6; id++) {
                sqls.add("INSERT INTO MR_OFF (ID) VALUES (" + id + ")");
            }
            List<String> batched = new ArrayList<>();
            new DefaultSQLExecutor().executeBatchInsert(recording(connection, batched), sqls, null, null, 0);

            assertEquals(6, count(connection, "SELECT COUNT(*) FROM MR_OFF"));
            assertEquals(6, batched.size(),
                    "the disabled switch must keep the legacy one-statement-per-row batch, saw " + batched);
        }
    }

    @Test
    void replaysTheChunkOnTheLegacyPathWhenTheMergedStatementIsRejected() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:multi_row_rejected;DB_CLOSE_DELAY=-1")) {
            putContext(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE MR_REJECT (ID INT PRIMARY KEY)");
            }

            // A statement the merge cannot parse (INSERT ... SELECT) keeps the whole chunk on the
            // legacy path, which is the same contract as a server-side rejection: the rows must
            // still land exactly once.
            List<String> sqls = new ArrayList<>();
            sqls.add("INSERT INTO MR_REJECT (ID) VALUES (1)");
            sqls.add("INSERT INTO MR_REJECT (ID) SELECT 2");

            List<String> batched = new ArrayList<>();
            new DefaultSQLExecutor().executeBatchInsert(recording(connection, batched), sqls, null, null, 0);

            assertEquals(2, count(connection, "SELECT COUNT(*) FROM MR_REJECT"),
                    "an unmergeable chunk must still import every row exactly once");
            assertEquals(2, batched.size(),
                    "an unmergeable chunk must stay on the legacy path, saw " + batched);
        }
    }

    @Test
    void mergedReplayKeepsCallerManagedTransactionState() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:multi_row_caller_transaction;DB_CLOSE_DELAY=-1")) {
            putContext(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE MR_CALLER (ID INT PRIMARY KEY)");
                statement.execute("INSERT INTO MR_CALLER (ID) VALUES (0)");
            }
            connection.setAutoCommit(false);

            List<String> sqls = new ArrayList<>();
            sqls.add("INSERT INTO MR_CALLER (ID) VALUES (1)");
            sqls.add("INSERT INTO MR_CALLER (ID) VALUES (2)");

            new DefaultSQLExecutor().executeBatchInsert(
                    rejectingMerged(connection), sqls, null, null, 0);

            assertEquals(3, count(connection, "SELECT COUNT(*) FROM MR_CALLER"),
                    "the speculative replay must not roll back work from the caller's transaction");
            assertEquals(Boolean.FALSE, connection.getAutoCommit(),
                    "the caller keeps ownership of the transaction");
            connection.rollback();
        }
    }

    private static Connection rejectingMerged(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                DefaultSQLExecutorMultiRowInsertTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (!"createStatement".equals(method.getName()) || !(result instanceof Statement)) {
                        return result;
                    }
                    List<String> batchSql = new ArrayList<>();
                    return Proxy.newProxyInstance(
                            DefaultSQLExecutorMultiRowInsertTest.class.getClassLoader(),
                            new Class<?>[]{Statement.class}, (stmtProxy, stmtMethod, stmtArgs) -> {
                                if ("addBatch".equals(stmtMethod.getName()) && stmtArgs != null
                                        && stmtArgs.length == 1 && stmtArgs[0] instanceof String sql) {
                                    batchSql.add(sql);
                                }
                                if ("executeBatch".equals(stmtMethod.getName())
                                        && batchSql.stream().anyMatch(DefaultSQLExecutorMultiRowInsertTest::isMerged)) {
                                    batchSql.clear();
                                    throw new SQLException("simulated merged statement rejection");
                                }
                                return invoke(result, stmtMethod, stmtArgs);
                            });
                });
    }

    private static boolean isMerged(String sql) {
        return sql != null && (sql.contains("), (") || sql.contains("),("));
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void putContext(Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(202L);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDatabaseName("");
        connectInfo.setSchemaName("PUBLIC");
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
    }

    /**
     * Wraps the connection so every {@code addBatch} payload is captured. The executor only reports
     * statement creation/closing through its listener, so the statement itself is the only place
     * where the merged SQL is observable.
     */
    private static Connection recording(Connection delegate, List<String> batched) {
        return (Connection) Proxy.newProxyInstance(
                DefaultSQLExecutorMultiRowInsertTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (!"createStatement".equals(method.getName()) || !(result instanceof Statement)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(
                            DefaultSQLExecutorMultiRowInsertTest.class.getClassLoader(),
                            new Class<?>[]{Statement.class}, (stmtProxy, stmtMethod, stmtArgs) -> {
                                if ("addBatch".equals(stmtMethod.getName()) && stmtArgs != null
                                        && stmtArgs.length == 1 && stmtArgs[0] instanceof String sql) {
                                    batched.add(sql);
                                }
                                return invoke(result, stmtMethod, stmtArgs);
                            });
                });
    }

    private static Object invoke(Object delegate, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static final class TestPlugin implements IPlugin {

        private final DBConfig dbConfig;

        private TestPlugin() {
            dbConfig = new DBConfig();
            dbConfig.setDbType(TEST_DB_TYPE);
            dbConfig.setDefaultDriverConfig(new DriverConfig());

        }

        @Override
        public DBConfig getDBConfig() {
            return dbConfig;
        }

        @Override
        public IDbMetaData getDbMetaData() {
            return new DefaultMetaService();
        }

        @Override
        public IDbManager getDbManager() {
            return new DefaultDBManager();
        }
    }
}
