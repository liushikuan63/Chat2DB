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

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Production executor, admission, metadata, shard parsing, SQL and commits against isolated H2. */
class ScopedCsvOptionsExecutionTest {
    @TempDir Path directory;

    @Test void schemaSourcesUseIndependentDialectRangeAndTypedValues() throws Exception {
        execute("SCHEMA", false);
    }

    @Test void databaseSourcesUseIndependentDialectRangeAndTypedValues() throws Exception {
        execute("DATABASE", false);
    }

    @Test void stagingSourcesKeepTypedValuesAndEmptyPolicy() throws Exception {
        execute("SCHEMA", true);
    }

    @Test void manifestSkipsInvalidTypedValueAndContinues() throws Exception {
        execute("SCHEMA", false, true);
    }

    @Test void stagingSkipsInvalidTypedValueAndContinues() throws Exception {
        execute("DATABASE", true, true);
    }

    @Test void csvTypedFailureKeepsCommittedPrefixAndDiscardsPendingRows() throws Exception {
        execute("TABLE", false, false);
    }

    private void execute(String scope, boolean staging) throws Exception { execute(scope, staging, false); }

    private void execute(String scope, boolean staging, boolean skipInvalid) throws Exception {
        String oldHome = System.getProperty("user.home");
        String oldCheckpointInterval = System.getProperty("chat2db.task.import.checkpoint-interval");
        if ("TABLE".equals(scope)) System.setProperty("chat2db.task.import.checkpoint-interval", "1");
        String type = "H2_SCOPED_CSV_OPTIONS";
        String url = "jdbc:h2:mem:scoped_options_" + UUID.randomUUID().toString().replace("-", "");
        IPlugin previous = Chat2DBContext.PLUGIN_MAP.get(type);
        System.setProperty("user.home", directory.toString());
        try (Connection observer = DriverManager.getConnection(url)) {
            for (String table : List.of("WITH_HEADER", "NO_HEADER")) {
                observer.createStatement().execute("CREATE TABLE " + table
                        + " (ID INT PRIMARY KEY, LABEL VARCHAR(80), D DATE, AMOUNT DECIMAL(10,2), BLANK VARCHAR(20), N DECIMAL(10,2))");
            }
            DBConfig config = new DBConfig();
            config.setDbType(type);
            config.setDefaultDriverConfig(new DriverConfig());
            Chat2DBContext.PLUGIN_MAP.put(type, new IPlugin() {
                public DBConfig getDBConfig() { return config; }
                public IDbManager getDbManager() {
                    return new DefaultDBManager() {
                        @Override public Connection getConnection(ConnectInfo info) {
                            try { return DriverManager.getConnection(url); }
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

            Path first = directory.resolve("header.csv");
            Files.writeString(first, "ignored intro\nID;LABEL;D;AMOUNT;BLANK;N\n99;outside;01/01/2000;9,00;x;9,00\n"
                    + "1;中文;31/12/2025;12,50;;NULL\n"
                    + (skipInvalid ? "5;bad;not-a-date;7,25;;NULL\n" : "")
                    + "2;kept;01/02/2026;7,25;;NULL\n98;outside;01/01/2000;9,00;x;9,00\n",
                    Charset.forName("UTF-16LE"));
            Path second = directory.resolve("headerless.csv");
            Files.writeString(second, "3|café|30.11.2024|3,75||NULL\n97|outside|01.01.2000|9,00|x|9,00\n",
                    Charset.forName("windows-1252"));
            CsvOptions a = CsvOptions.defaults();
            a.setEncoding("UTF-16LE"); a.setDelimiter(";"); a.setHeaderRow(2);
            a.setDataStartRow(4); a.setDataEndRow(skipInvalid ? 6 : 5); a.setDateOrder("DMY");
            a.setDateDelimiter("/"); a.setYearDelimiter("/"); a.setDecimalSymbol(",");
            a.setEmptyAsNull(false);
            CsvOptions b = CsvOptions.defaults();
            b.setEncoding("windows-1252"); b.setDelimiter("|"); b.setHasHeader(false);
            b.setDataStartRow(1); b.setDataEndRow(1); b.setDateOrder("DMY");
            b.setDateDelimiter("."); b.setYearDelimiter("."); b.setDecimalSymbol(",");
            b.setEmptyAsNull(true);
            List<String> names = List.of("ID", "LABEL", "D", "AMOUNT", "BLANK", "N");
            List<ImportColumnMapping> mappings = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                mappings.add(ImportColumnMapping.builder().sourceColumn("column_" + (i + 1))
                        .targetColumn(names.get(i)).build());
            }
            ImportTaskSpec spec = ImportTaskSpec.builder().taskType("DATA_FILE_IMPORT").format("CSV")
                    .scope(scope).mode("STANDARD")
                    .options(ImportOptions.builder().nullString("NULL").onError(skipInvalid ? "SKIP" : "ABORT").maxErrors(2).build())
                    .target(TaskTargetSnapshot.builder().databaseName(observer.getCatalog()).schemaName("PUBLIC").build())
                    .tableSources(List.of(
                            ImportTableSource.builder().databaseName(observer.getCatalog()).schemaName("PUBLIC").tableName("WITH_HEADER")
                                    .sourceFile(first.toString()).format("CSV").csvOptions(a).build(),
                            ImportTableSource.builder().databaseName(observer.getCatalog()).schemaName("PUBLIC").tableName("NO_HEADER")
                                    .sourceFile(second.toString()).format("CSV").csvOptions(b)
                                    .columnMappings(mappings).build()))
                    .build();
            if (staging) spec.setStagingPolicy(ImportStagingPolicy.builder().enabled(true).build());
            if ("TABLE".equals(scope)) {
                StringBuilder text = new StringBuilder("intro\nID;LABEL;D;AMOUNT;BLANK;N\n99;ignored;01/01/2000;1,00;;NULL\n");
                for (int row = 1; row <= 501; row++) {
                    text.append(row).append(";prefix;31/12/2025;12,50;;NULL\n");
                }
                text.append("502;bad;invalid-date;1,00;;NULL\n503;later;31/12/2025;1,00;;NULL\n");
                Files.writeString(first, text.toString(), Charset.forName("UTF-16LE"));
                a.setDataEndRow(null);
                spec.getTarget().setTableName("WITH_HEADER");
                spec.setTableSources(null); spec.setSourceFile(first.toString()); spec.setCsvOptions(a);
            }
            // Persist/reload the same shape the controller submits before the actual executor sees it.
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
            if ("TABLE".equals(scope)) {
                ImportTaskSpec single = spec;
                assertThrows(TaskExecutionException.class, () -> executor.execute(single, context));
                try (ResultSet rows = observer.createStatement().executeQuery("SELECT COUNT(*), MAX(ID) FROM WITH_HEADER")) {
                    assertTrue(rows.next()); assertEquals(500, rows.getInt(1)); assertEquals(500, rows.getInt(2));
                }
                assertFalse(states.isEmpty(), "the committed prefix must retain a durable watermark");
                assertTrue(states.values().stream().allMatch(state -> Long.valueOf(500).equals(state.getRowsDone())));
                context.closeQuietly();
                return;
            }
            executor.execute(spec, context);
            assertNotNull(manifest.get(), "the real executor must create a scoped manifest");
            assertEquals(skipInvalid ? 4L : 3L, manifest.get().getTotalEstimatedRows());
            if (!staging) {
                assertEquals(2, states.size());
                assertTrue(states.values().stream().allMatch(state -> "MANIFEST_DONE".equals(state.getKind())));
            }
            try (ResultSet rows = observer.createStatement().executeQuery("SELECT * FROM WITH_HEADER ORDER BY ID")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt("ID")); assertEquals("中文", rows.getString("LABEL"));
                assertEquals("2025-12-31", rows.getDate("D").toString());
                assertEquals("12.50", rows.getBigDecimal("AMOUNT").toPlainString()); assertEquals("", rows.getString("BLANK")); assertNull(rows.getBigDecimal("N"));
                assertTrue(rows.next()); assertEquals(2, rows.getInt("ID"));
                assertEquals("2026-02-01", rows.getDate("D").toString()); assertEquals("7.25", rows.getBigDecimal("AMOUNT").toPlainString());
                assertFalse(rows.next());
            }
            try (ResultSet rows = observer.createStatement().executeQuery("SELECT * FROM NO_HEADER")) {
                assertTrue(rows.next()); assertEquals(3, rows.getInt("ID")); assertEquals("café", rows.getString("LABEL"));
                assertEquals("2024-11-30", rows.getDate("D").toString());
                assertEquals("3.75", rows.getBigDecimal("AMOUNT").toPlainString()); assertNull(rows.getString("BLANK")); assertNull(rows.getBigDecimal("N"));
                assertFalse(rows.next());
            }
            context.closeQuietly();
        } finally {
            Chat2DBContext.clearContextReference();
            if (previous == null) Chat2DBContext.PLUGIN_MAP.remove(type);
            else Chat2DBContext.PLUGIN_MAP.put(type, previous);
            System.setProperty("user.home", oldHome);
            if (oldCheckpointInterval == null) System.clearProperty("chat2db.task.import.checkpoint-interval");
            else System.setProperty("chat2db.task.import.checkpoint-interval", oldCheckpointInterval);
        }
    }
}
