package ai.chat2db.community.domain.core.impl.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The VB-011 chain: an import path that defers row durability to a transaction commit must not leave
 * row watermarks behind when its attempt fails and the transaction rolls back. If it does, the retry
 * believes those rows are already in the table, skips them, and the user silently loses data.
 *
 * <p>The test reproduces the real sequence: attempt one fails inside an open transaction, the
 * transaction is rolled back exactly as a failed shard does, and attempt two re-runs the same task.
 * The assertions name the rows below the failure point, because those are the rows a stale watermark
 * would skip.
 */
class ImportDeferredDurabilityChainTest {

    private static final String DB_TYPE = "IMPORT_DEFERRED_TEST";
    private static final String H2_DRIVER_NAME = "import-deferred-h2.jar";
    private static final int ROWS = 5000;
    private static final int POISON_ID = 1500;

    private static String previousUserHome;

    @TempDir
    Path tempDirectory;

    private Connection connection;
    private IPlugin previousPlugin;
    private RecordingStorage storage;

    @BeforeAll
    static void isolateHomeAndSeedDriver() throws Exception {
        previousUserHome = System.getProperty("user.home");
        File tempHome = Files.createTempDirectory("chat2db-import-deferred-home").toFile();
        System.setProperty("user.home", tempHome.getAbsolutePath());
        File libDir = new File(JdbcDriverConstants.DRIVER_LIB_PATH);
        libDir.mkdirs();
        File h2Jar = new File(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.copy(h2Jar.toPath(), new File(libDir, H2_DRIVER_NAME).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    @AfterAll
    static void restoreHome() {
        System.setProperty("user.home", previousUserHome);
    }

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("chat2db.task.import.journal-interval", "1");
        System.setProperty("chat2db.task.import.checkpoint-interval", "1");
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return new DefaultMetaService();
            }
        });
        connection = DriverManager.getConnection("jdbc:h2:mem:deferred_chain");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE BULK_ROWS (ID INT PRIMARY KEY, NAME VARCHAR(50))");
            statement.execute("INSERT INTO BULK_ROWS VALUES (" + POISON_ID + ", 'poison')");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setJdbcDriverClass("org.h2.Driver");
        driverConfig.setJdbcDriver(H2_DRIVER_NAME);
        connectInfo.setDriverConfig(driverConfig);
        connectInfo.setUrl("jdbc:h2:mem:deferred_chain");
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
        storage = new RecordingStorage();
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
        System.clearProperty("chat2db.task.import.journal-interval");
        System.clearProperty("chat2db.task.import.checkpoint-interval");
        connection.close();
    }

    @Test
    void aRolledBackDeferredAttemptLeavesNoWatermarkSoTheRetryKeepsEveryRow() throws Exception {
        Path csv = writeCsv();

        // The shard transaction: everything the attempt writes is discarded by the rollback below.
        connection.setAutoCommit(false);
        assertThrows(TaskExecutionException.class,
                () -> new CSVImporter().run(csvSpec(csv, "FAIL_FAST"), deferringContextFor()),
                "the poison row must abort a FAIL_FAST import");
        connection.rollback();
        connection.setAutoCommit(true);

        assertTrue(storage.resumeStates.stream().noneMatch(state -> state.getRowsDone() != null
                        && state.getRowsDone() > 0),
                "a rolled-back attempt must not leave a durable row watermark: " + storage.resumeStates);
        assertEquals(1, countRows(), "the rollback must discard everything the failed attempt wrote");

        // The retry of the same task has no durable progress to trust, so it must import every row.
        new CSVImporter().run(csvSpec(csv, "SKIP"), deferringContextFor());

        assertEquals(ROWS, countRows(), "every id must be present exactly once after the retry");
        assertEquals(ROWS, countDistinctIds(), "the retry must not duplicate rows");
        assertEquals(1, countIds(1), "the first row must not be skipped as if it were durable");
        assertEquals(1, countIds(POISON_ID - 1), "the row just below the failure point must not be skipped");
    }

    /** A context that reports the shard contract: durability arrives with the commit, not before. */
    private TaskExecutionContext deferringContextFor() {
        TaskExecutionContextImpl delegate = new TaskExecutionContextImpl(
                storage.proxy().create(Task.builder()
                                .type("DATA_FILE_IMPORT").name("deferred")
                                .target(TaskTargetSnapshot.builder().dataSourceId(1L).build()).build(),
                        TaskEvent.builder()
                                .level("INFO").code("TASK_CREATED").message("created").build()).getId(),
                new RunningTask(1L, () -> { }), storage.proxy(), new ArtifactServiceImpl());
        return (TaskExecutionContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {TaskExecutionContext.class}, (proxy, method, args) -> {
                    if ("defersRowDurabilityToCommit".equals(method.getName())) {
                        return true;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private Path writeCsv() throws Exception {
        Path csv = tempDirectory.resolve("deferred.csv");
        StringBuilder content = new StringBuilder("ID,NAME\n");
        for (int id = 1; id <= ROWS; id++) {
            content.append(id).append(",name-").append(id).append('\n');
        }
        Files.writeString(csv, content.toString(), StandardCharsets.UTF_8);
        return csv;
    }

    private ImportTaskSpec csvSpec(Path csv, String onError) {
        return ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(csv.toString())
                .format("CSV")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("BULK_ROWS").build())
                .options(ImportOptions.builder()
                        .charset("UTF-8")
                        .delimiter(",")
                        .onError(onError)
                        .maxErrors(100)
                        .columnMappings(java.util.List.of(
                                new ImportColumnMapping("ID", "ID"),
                                new ImportColumnMapping("NAME", "NAME")))
                        .build())
                .build();
    }

    /**
     * Records only what the chain needs to be observable: the resume states the import persists. A
     * dynamic proxy keeps the test focused on that, instead of re-implementing the whole storage API.
     */
    private static final class RecordingStorage {

        private final List<ResumeState> resumeStates = new ArrayList<>();

        private final TaskStorage storage = (TaskStorage) Proxy.newProxyInstance(
                RecordingStorage.class.getClassLoader(), new Class<?>[] {TaskStorage.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "create" -> {
                        Task task = (Task) args[0];
                        task.setId(1L);
                        yield task;
                    }
                    case "saveResumeState" -> {
                        resumeStates.add((ResumeState) args[1]);
                        yield null;
                    }
                    case "listResumeStates" -> List.copyOf(resumeStates);
                    case "clearResumeStates" -> {
                        resumeStates.clear();
                        yield null;
                    }
                    case "appendEvent" -> args[0];
                    case "updateProgressIfRunning", "compareAndSetStatus" -> true;
                    case "listArtifacts", "listResumableTasks", "listEvents", "listEventsBefore" -> List.of();
                    case "get" -> java.util.Optional.empty();
                    case "saveArtifact", "deleteArtifact" -> null;
                    default -> defaultValue(method.getReturnType());
                });

        TaskStorage proxy() {
            return storage;
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == int.class) {
                return 0;
            }
            return null;
        }
    }

    private int countRows() throws Exception {
        return scalar("SELECT COUNT(*) FROM BULK_ROWS");
    }

    private int countDistinctIds() throws Exception {
        return scalar("SELECT COUNT(DISTINCT ID) FROM BULK_ROWS");
    }

    private int countIds(int id) throws Exception {
        return scalar("SELECT COUNT(*) FROM BULK_ROWS WHERE ID = " + id);
    }

    private int scalar(String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
