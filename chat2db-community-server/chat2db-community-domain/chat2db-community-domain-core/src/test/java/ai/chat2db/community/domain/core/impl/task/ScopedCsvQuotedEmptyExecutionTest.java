package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.*;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.impl.task.executor.DataFileImportTaskExecutor;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real scoped executor and H2 constraints distinguish quoted empty text from SQL NULL. */
class ScopedCsvQuotedEmptyExecutionTest {
    @TempDir Path directory;
    @Test void schemaNullableDistinguishesQuotedEmpty() throws Exception { execute("SCHEMA", false, false, false); }
    @Test void databaseNullableDistinguishesQuotedEmpty() throws Exception { execute("DATABASE", false, false, false); }
    @Test void stagingNullableDistinguishesQuotedEmpty() throws Exception { execute("SCHEMA", true, false, false); }
    @Test void schemaNotNullAcceptsQuotedEmpty() throws Exception { execute("SCHEMA", false, true, false); }
    @Test void databaseNotNullAcceptsQuotedEmpty() throws Exception { execute("DATABASE", false, true, false); }
    @Test void stagingNotNullAcceptsQuotedEmpty() throws Exception { execute("DATABASE", true, true, false); }
    @Test void manifestExplicitEmptyNullLiteralTakesPrecedence() throws Exception { execute("SCHEMA", false, false, true); }
    @Test void stagingExplicitEmptyNullLiteralTakesPrecedence() throws Exception { execute("DATABASE", true, false, true); }

    @Test void manifestQuotedEmptyNumericUsesSkipPolicy() throws Exception { execute("SCHEMA", false, false, false, true); }
    @Test void stagingQuotedEmptyNumericUsesSkipPolicy() throws Exception { execute("DATABASE", true, false, false, true); }

    @Test void manifestBadNumericUsesSkipPolicy() throws Exception { execute("SCHEMA", false, false, false, true, true); }
    @Test void stagingBadNumericUsesSkipPolicy() throws Exception { execute("DATABASE", true, false, false, true, true); }

    private void execute(String scope, boolean staging, boolean notNull, boolean emptyNullLiteral) throws Exception {
        execute(scope, staging, notNull, emptyNullLiteral, false);
    }

    private void execute(String scope, boolean staging, boolean notNull, boolean emptyNullLiteral, boolean numeric) throws Exception {
        execute(scope, staging, notNull, emptyNullLiteral, numeric, false);
    }

    @Test void failedBatchSavepointPreservesEarlierBatch() throws Exception {
        execute("SCHEMA", false, false, false, true, true, 500, "");
    }
    @Test void failedSavepointRollbackAbortsWithoutReplay() throws Exception {
        execute("DATABASE", false, false, false, true, true, 0, "rollback");
    }
    @Test void unsupportedSavepointAbortsBeforeWriting() throws Exception {
        execute("SCHEMA", false, false, false, true, true, 0, "unsupported");
    }

    private void execute(String scope, boolean staging, boolean notNull, boolean emptyNullLiteral, boolean numeric, boolean badNumeric) throws Exception {
        execute(scope, staging, notNull, emptyNullLiteral, numeric, badNumeric, 0, "");
    }

    private void execute(String scope, boolean staging, boolean notNull, boolean emptyNullLiteral,
            boolean numeric, boolean badNumeric, int prefixRows, String fault) throws Exception {
        String oldHome = System.getProperty("user.home");
        String type = "H2_SCOPED_QUOTED_EMPTY";
        String url = "jdbc:h2:mem:quoted_empty_" + UUID.randomUUID().toString().replace("-", "");
        AtomicInteger savepoints = new AtomicInteger();
        AtomicInteger savepointRollbacks = new AtomicInteger();
        IPlugin previous = Chat2DBContext.PLUGIN_MAP.get(type);
        System.setProperty("user.home", directory.toString());
        try (Connection observer = DriverManager.getConnection(url)) {
            observer.createStatement().execute("CREATE TABLE EMPTY_VALUES (ID INT PRIMARY KEY, TXT " + (numeric ? "INTEGER" : "VARCHAR(80)")
                    + (notNull ? " NOT NULL" : "") + ")");
            DBConfig config = new DBConfig();
            config.setDbType(type);
            config.setDefaultDriverConfig(new DriverConfig());
            Chat2DBContext.PLUGIN_MAP.put(type, new IPlugin() {
                public DBConfig getDBConfig() { return config; }
                public IDbManager getDbManager() {
                    return new DefaultDBManager() {
                        @Override public Connection getConnection(ConnectInfo info) {
                            try {
                                Connection delegate = DriverManager.getConnection(url);
                                if (fault.isEmpty()) return delegate;
                                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                    if (method.getName().equals("setSavepoint")) {
                                        savepoints.incrementAndGet();
                                        if (fault.equals("unsupported")) throw new SQLFeatureNotSupportedException("fixture no savepoints");
                                    }
                                    if (method.getName().equals("rollback") && args != null && args.length == 1) {
                                        savepointRollbacks.incrementAndGet();
                                        throw new SQLException("fixture rollback savepoint failure");
                                    }
                                    try { return method.invoke(delegate, args); }
                                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                                });
                            }
                            catch (SQLException failure) { throw new RuntimeException(failure); }
                        }
                    };
                }
            });
            ConnectInfo info = new ConnectInfo();
            info.setDbType(type);
            info.setUrl(url);
            info.setSchemaName("PUBLIC");
            info.setDatabaseName(observer.getCatalog());
            info.setDriverConfig(new DriverConfig());
            info.setConnection(observer);
            Chat2DBContext.putContext(info);

            Path file = directory.resolve("quoted.csv");
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < prefixRows; i++) prefix.append(i + 10).append(";9\n");
            Files.writeString(file, "ID;TXT\n" + prefix + (notNull ? "" : badNumeric ? "1;4\n" : "1;\n") + (badNumeric ? "2;bad\n" : "2;\"\"\n") + (numeric ? "3;7\n" : ""));
            CsvOptions options = CsvOptions.defaults();
            options.setDelimiter(";");
            assertTrue(options.getEmptyAsNull());
            ImportTaskSpec spec = ImportTaskSpec.builder().taskType("DATA_FILE_IMPORT").format("CSV")
                    .scope(scope).mode("STANDARD")
                    .target(TaskTargetSnapshot.builder().databaseName(observer.getCatalog()).schemaName("PUBLIC").build())
                    .options(ImportOptions.builder().nullString(emptyNullLiteral ? "" : null).onError(numeric ? "SKIP" : "ABORT").maxErrors(2).build())
                    .tableSources(List.of(ImportTableSource.builder().databaseName(observer.getCatalog())
                            .schemaName("PUBLIC").tableName("EMPTY_VALUES").sourceFile(file.toString())
                            .format("CSV").csvOptions(options).build())).build();
            if (staging) spec.setStagingPolicy(ImportStagingPolicy.builder().enabled(true).build());
            spec = com.alibaba.fastjson2.JSON.parseObject(com.alibaba.fastjson2.JSON.toJSONString(spec), ImportTaskSpec.class);
            AtomicReference<ImportManifest> manifest = new AtomicReference<>();
            Map<Integer, ResumeState> states = new ConcurrentHashMap<>();
            TaskStorage storage = (TaskStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "loadImportManifest": return Optional.ofNullable(manifest.get());
                    case "saveImportManifest": manifest.set((ImportManifest) args[1]); return null;
                    case "listResumeStates": return new ArrayList<>(states.values());
                    case "saveResumeState": {
                        ResumeState state = (ResumeState) args[1]; states.put(state.getShardNo(), state); return null;
                    }
                    case "compareAndSetResumeState": {
                        synchronized (states) {
                            int index = (Integer) args[1];
                            if (!args[2].equals(states.get(index).getKind())) return false;
                            states.put(index, (ResumeState) args[3]); return true;
                        }
                    }
                    case "get": return Optional.empty();
                    case "listEvents": return List.of();
                    default: return method.getReturnType() == boolean.class ? false : null;
                }
            });
            DataFileImportTaskExecutor executor = new DataFileImportTaskExecutor();
            var storageField = DataFileImportTaskExecutor.class.getDeclaredField("taskStorage");
            storageField.setAccessible(true); storageField.set(executor, storage);
            TaskExecutionContextImpl context = new TaskExecutionContextImpl(77L, new RunningTask(77L),
                    storage, new ArtifactServiceImpl());
            try {
                if (!fault.isEmpty()) {
                    ImportTaskSpec finalSpec = spec;
                    assertThrows(RuntimeException.class, () -> executor.execute(finalSpec, context));
                    assertEquals(1, savepoints.get(), "must not enter unsafe replay after savepoint failure");
                    assertEquals(fault.equals("rollback") ? 1 : 0, savepointRollbacks.get());
                    try (ResultSet count = observer.createStatement().executeQuery("SELECT COUNT(*) FROM EMPTY_VALUES")) {
                        assertTrue(count.next()); assertEquals(0, count.getInt(1), "outer shard must roll back all pending writes");
                    }
                    return;
                }
                executor.execute(spec, context);
            } finally {
                context.closeQuietly();
            }
            assertNotNull(manifest.get(), "must execute scoped manifest path");
            assertEquals(prefixRows + (numeric ? 3L : notNull ? 1L : 2L), manifest.get().getTotalEstimatedRows());
            try (ResultSet rows = observer.createStatement().executeQuery("SELECT ID, TXT, TXT IS NULL AS IS_NULL FROM EMPTY_VALUES ORDER BY ID")) {
                if (!notNull) {
                    assertTrue(rows.next()); assertEquals(1, rows.getInt("ID"));
                    if (badNumeric) { assertEquals("4", rows.getString("TXT")); assertFalse(rows.getBoolean("IS_NULL")); }
                    else { assertNull(rows.getString("TXT")); assertTrue(rows.getBoolean("IS_NULL")); }
                }
                assertTrue(rows.next()); assertEquals(numeric ? 3 : 2, rows.getInt("ID"));
                if (emptyNullLiteral) { assertNull(rows.getString("TXT")); assertTrue(rows.getBoolean("IS_NULL")); }
                else { assertEquals(numeric ? "7" : "", rows.getString("TXT")); assertFalse(rows.getBoolean("IS_NULL")); }
                for (int i = 0; i < prefixRows; i++) {
                    assertTrue(rows.next()); assertEquals(i + 10, rows.getInt("ID")); assertEquals("9", rows.getString("TXT"));
                }
                assertFalse(rows.next());
            }
        } finally {
            Chat2DBContext.clearContextReference();
            if (previous == null) Chat2DBContext.PLUGIN_MAP.remove(type);
            else Chat2DBContext.PLUGIN_MAP.put(type, previous);
            System.setProperty("user.home", oldHome);
        }
    }
}
