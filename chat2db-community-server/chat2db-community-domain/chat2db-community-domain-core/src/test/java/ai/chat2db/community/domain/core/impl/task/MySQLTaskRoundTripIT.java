package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.core.impl.task.executor.DataFileImportTaskExecutor;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.plugin.mysql.MysqlDBManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real, isolated MySQL export/import, checkpoint resume, manifest and capacity integration. */
class MySQLTaskRoundTripIT {

    private static final String DB_TYPE = "MYSQL_TASK_IT";

    private static final String MYSQL_DRIVER_NAME = "mysql-it-connector.jar";

    private static String previousUserHome;

    private static final int ROWS = 1000;

    @TempDir
    Path tempDirectory;

    @BeforeAll
    static void seedMysqlDriver() throws Exception {
        // JdbcJarUtils cannot load an absolute jar path (it treats the input as a URI), so copy
        // the MySQL driver into the driver library and reference it by file name, mirroring
        // ShardedKeysetExportTest.
        previousUserHome = System.getProperty("user.home");
        File tempHome = Files.createTempDirectory("chat2db-mysql-it-home").toFile();
        System.setProperty("user.home", tempHome.getAbsolutePath());
        File libDir = new File(JdbcDriverConstants.DRIVER_LIB_PATH);
        libDir.mkdirs();
        File mysqlJar = new File(com.mysql.cj.jdbc.Driver.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        Files.copy(mysqlJar.toPath(), new File(libDir, MYSQL_DRIVER_NAME).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    @AfterAll
    static void restoreHome() {
        System.setProperty("user.home", previousUserHome);
    }

    private Connection connection;
    private IPlugin previousPlugin;
    private StorageStub storage;
    private final IDbManager mysqlDbManager = new MysqlDBManager();
    private String url;
    private String database;

    @BeforeEach
    void setUp() throws Exception {
        RequiredDatabaseFixture fixture = RequiredDatabaseFixture.load("MYSQL", 3306);
        String host = fixture.host();
        String port = String.valueOf(fixture.port());
        String user = fixture.user();
        String password = fixture.password();
        database = fixture.database();

        String bootstrapUrl = "jdbc:mysql://" + host + ":" + port + "/?allowPublicKeyRetrieval=true"
                + "&useSSL=false&serverTimezone=UTC&connectTimeout=5000&socketTimeout=120000"
                + "&rewriteBatchedStatements=true";
        connection = DriverManager.getConnection(bootstrapUrl, user, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database);
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
            statement.execute("USE " + database);
            statement.execute("CREATE TABLE C2D_SRC (ID INT PRIMARY KEY, NAME VARCHAR(50), VAL INT)");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO C2D_SRC (ID, NAME, VAL) VALUES (?, ?, ?)")) {
            for (int id = 1; id <= ROWS; id++) {
                insert.setInt(1, id);
                insert.setString(2, "row-" + id);
                insert.setInt(3, id * 2);
                insert.addBatch();
                if (id % 200 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
        }

        url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?allowPublicKeyRetrieval=true"
                + "&useSSL=false&serverTimezone=UTC&connectTimeout=5000&socketTimeout=120000"
                + "&rewriteBatchedStatements=true";

        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(mysqlDriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return new DefaultMetaService();
            }

            @Override
            public IDbManager getDbManager() {
                return mysqlDbManager;
            }
        });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setUrl(url);
        connectInfo.setUser(user);
        connectInfo.setPassword(password);
        connectInfo.setDriverConfig(mysqlDriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
        storage = new StorageStub();
        System.setProperty("chat2db.task.import.parallel.min-bytes", "1");
        System.setProperty("chat2db.task.import.parallel.min-rows", "1");
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
        System.clearProperty("chat2db.task.import.checkpoint-interval");
        System.clearProperty("chat2db.task.import.parallelism");
        System.clearProperty("chat2db.task.import.parallel.min-bytes");
        System.clearProperty("chat2db.task.import.parallel.min-rows");
        System.clearProperty("chat2db.task.import.csv-shard-target-bytes");
        if (connection != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database);
            } catch (Exception cleanupFailure) {
                // the database is a named local fixture; leave it but never mask the test result
            }
            connection.close();
        }
    }

    private static DriverConfig mysqlDriverConfig() {
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setJdbcDriver(MYSQL_DRIVER_NAME);
        driverConfig.setJdbcDriverClass("com.mysql.cj.jdbc.Driver");
        return driverConfig;
    }

    private TaskExecutionContextImpl contextFor() {
        Long taskId = storage.create(Task.builder().type("TASK_IT").name("mysql-it")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).build()).build(),
                TaskEvent.builder()
                .level("INFO").code("TASK_CREATED").message("created").build()).getId();
        return new TaskExecutionContextImpl(taskId, new RunningTask(taskId), storage, new ArtifactServiceImpl());
    }

    private void createCopyTable(String tableName) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + tableName
                    + " (ID INT PRIMARY KEY, NAME VARCHAR(50), VAL INT)");
        }
    }

    private long countRows(String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void importCsvInto(String tableName, File csv) {
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(csv.getAbsolutePath())
                .importFileId("mysql-roundtrip-staged")
                .format("CSV")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).databaseName(database)
                        .tableName(tableName).build())
                .mode("ULTRA_FAST")
                .confirmedNoStrongRelations(true)
                .options(ImportOptions.builder()
                        .charset("UTF-8")
                        .delimiter(",")
                        .onError("ABORT")
                        .columnMappings(List.of(
                                new ImportColumnMapping("ID", "ID"),
                                new ImportColumnMapping("NAME", "NAME"),
                                new ImportColumnMapping("VAL", "VAL")))
                        .build())
                .build();
        new CSVImporter().run(spec, contextFor());
    }

    @Test
    void exportedCsvRoundTripsAllValuesIncludingNullAndQuotedText() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE C2D_SRC SET NAME = 'comma, quote\" and \u4e2d\u6587 \ud83d\ude00\nnext line' WHERE ID = 1");
            statement.executeUpdate("UPDATE C2D_SRC SET NAME = NULL, VAL = NULL WHERE ID = 2");
        }
        File exported = tempDirectory.resolve("roundtrip.csv").toFile();
        new ai.chat2db.community.domain.core.impl.task.export.excel.CsvDataExporter(
                new ExportCellProcessorChain(List.of()), new SqlExecutionPolicyManager(List.of()))
                .run(ExportTaskSpec.builder().format("CSV").containsHeader(true).tableNames(List.of("C2D_SRC"))
                        .target(TaskTargetSnapshot.builder().dataSourceId(1L).databaseName(database).build())
                        .build(), contextFor(), exported);
        assertTrue(exported.length() > 0);
        System.out.println("CSV_DEFAULT_CHARSET " + java.nio.charset.Charset.defaultCharset().name());
        String exportedText = Files.readString(exported.toPath(), StandardCharsets.UTF_8);
        assertTrue(exportedText.contains("\u4e2d\u6587 \ud83d\ude00"));
        createCopyTable("C2D_ROUNDTRIP");
        ImportTaskSpec spec = standardSpec("C2D_ROUNDTRIP", exported.toPath());
        new CSVImporter().run(spec, contextFor());
        assertExactCopy("C2D_ROUNDTRIP");
    }

    @Test
    void standardImportResumesFromPersistedCheckpointExactlyOnce() throws Exception {
        System.setProperty("chat2db.task.import.checkpoint-interval", "1");
        Path csv = tempDirectory.resolve("resume.csv");
        StringBuilder content = new StringBuilder("ID,NAME,VAL\n");
        for (int id = 1; id <= ROWS; id++) {
            content.append(id).append(",row-").append(id).append(',').append(id * 2).append('\n');
        }
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        createCopyTable("C2D_RESUME");
        ImportTaskSpec spec = standardSpec("C2D_RESUME", csv);
        RecordingContext interrupted = new RecordingContext(1);
        assertThrows(TaskCancelledException.class, () -> new CSVImporter().run(spec, interrupted));
        long prefix = countRows("C2D_RESUME");
        assertTrue(prefix > 0 && prefix < ROWS, "interruption must leave a committed prefix");
        assertTrue(interrupted.saved.stream().anyMatch(state -> state.getRowsDone() == prefix));
        Path checkpoint = tempDirectory.resolve("checkpoint.json");
        Files.writeString(checkpoint, com.alibaba.fastjson2.JSON.toJSONString(interrupted.saved));
        RecordingContext resumed = new RecordingContext(Integer.MAX_VALUE);
        resumed.resumeStates.addAll(com.alibaba.fastjson2.JSON.parseArray(
                Files.readString(checkpoint), ResumeState.class));
        // A new task id avoids recovering the first attempt's in-process/journal state.
        resumed.id = 100L;
        new CSVImporter().run(spec, resumed);
        assertExactCopy("C2D_RESUME");
    }

    private ImportTaskSpec standardSpec(String table, Path csv) {
        return ImportTaskSpec.builder().taskType("DATA_FILE_IMPORT").format("CSV").mode("STANDARD")
                .sourceFile(csv.toString()).target(TaskTargetSnapshot.builder().dataSourceId(1L)
                        .databaseName(database).tableName(table).build())
                .options(ImportOptions.builder().charset("UTF-8").delimiter(",").onError("ABORT")
                        .columnMappings(List.of(new ImportColumnMapping("ID", "ID"),
                                new ImportColumnMapping("NAME", "NAME"), new ImportColumnMapping("VAL", "VAL")))
                        .build()).build();
    }

    private void assertExactCopy(String table) throws Exception {
        assertEquals(ROWS, countRows(table));
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT COUNT(*) FROM C2D_SRC s JOIN " + table
                        + " t ON s.ID = t.ID WHERE (s.NAME <=> t.NAME) AND (s.VAL <=> t.VAL)")) {
            assertTrue(rows.next());
            assertEquals(ROWS, rows.getLong(1), "every original value and SQL NULL must survive exactly once");
        }
    }

    @Test
    void parallelImportHandlesMysqlTarget() throws Exception {
        System.setProperty("chat2db.task.import.parallelism", "4");
        Path csv = tempDirectory.resolve("parallel.csv");
        StringBuilder content = new StringBuilder("ID,NAME,VAL\n");
        for (int id = 1; id <= ROWS * 2; id++) {
            content.append(id).append(",par-").append(id).append(',').append(id * 3).append('\n');
        }
        Files.writeString(csv, content.toString(), StandardCharsets.UTF_8);

        createCopyTable("C2D_PAR");
        importCsvInto("C2D_PAR", csv.toFile());

        assertEquals(ROWS * 2, countRows("C2D_PAR"), "parallel workers must insert every row exactly once");
        try (Statement statement = connection.createStatement();
             ResultSet distinct = statement.executeQuery("SELECT COUNT(DISTINCT ID) FROM C2D_PAR")) {
            assertTrue(distinct.next());
            assertEquals(ROWS * 2, distinct.getLong(1), "no duplicated ids");
        }
    }

    @Test
    void manifestExecutorImportsAndCleansMysqlShards() throws Exception {
        System.setProperty("chat2db.task.import.parallelism", "4");
        System.setProperty("chat2db.task.import.csv-shard-target-bytes", "4096");
        Path csv = tempDirectory.resolve("manifest.csv");
        StringBuilder content = new StringBuilder("ID,NAME,VAL\n");
        for (int id = 1; id <= ROWS * 2; id++) {
            content.append(id).append(",manifest-").append(id).append(',').append(id * 5).append('\n');
        }
        Files.writeString(csv, content.toString(), StandardCharsets.UTF_8);
        createCopyTable("C2D_MANIFEST");

        ImportTaskSpec spec = ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT").sourceFile(csv.toString()).importFileId("mysql-manifest-staged")
                .format("CSV").mode("ULTRA_FAST").confirmedNoStrongRelations(true)
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).databaseName(database)
                        .tableName("C2D_MANIFEST").build())
                .options(ImportOptions.builder().charset("UTF-8").delimiter(",").onError("ABORT")
                        .columnMappings(List.of(new ImportColumnMapping("ID", "ID"),
                                new ImportColumnMapping("NAME", "NAME"),
                                new ImportColumnMapping("VAL", "VAL"))).build())
                .build();
        TaskExecutionContextImpl context = contextFor();
        DataFileImportTaskExecutor executor = new DataFileImportTaskExecutor();
        setField(executor, "taskStorage", storage);
        setField(executor, "importFileStagingService",
                (IImportFileStagingService) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{IImportFileStagingService.class}, (proxy, method, args) -> null));

        executor.execute(spec, context);

        ImportManifest manifest = storage.manifest.orElseThrow();
        assertTrue(manifest.getShards().size() > 1, "small target size must create multiple manifest shards");
        assertEquals(ROWS * 2, countRows("C2D_MANIFEST"));
        try (Statement statement = connection.createStatement();
             ResultSet distinct = statement.executeQuery("SELECT COUNT(DISTINCT ID) FROM C2D_MANIFEST")) {
            assertTrue(distinct.next());
            assertEquals(ROWS * 2, distinct.getLong(1), "manifest shards must not duplicate ids");
        }
        assertEquals(manifest.getShards().size(), storage.states.stream()
                .filter(state -> "MANIFEST_DONE".equals(state.getKind())).count());
        assertTrue(manifest.getShards().stream().noneMatch(shard -> Files.exists(Path.of(shard.getSourcePath()))),
                "successful execution must remove generated shard files");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void mysqlResourceProbeReadsLiveCapacityReplicationAndTriggerFacts() {
        ImportResourceSnapshot resources = mysqlDbManager.probeImportResources(connection, database, null);

        assertTrue(resources.connectionCapacityKnown());
        assertTrue(resources.maxConnections() > resources.activeConnections());
        assertTrue(resources.replicationStatusKnown());
        assertEquals(0, resources.triggerCount());
        assertTrue(!resources.diskCapacityKnown());
    }

    private static final class RecordingContext implements TaskExecutionContext {

        private Long id = 99L;
        private final List<ResumeState> saved = new ArrayList<>();
        private final List<ResumeState> resumeStates = new ArrayList<>();
        private final int cancelAfterCheckpoints;
        private int checkpointCalls;

        private RecordingContext(int cancelAfterCheckpoints) {
            this.cancelAfterCheckpoints = cancelAfterCheckpoints;
        }

        @Override
        public Long taskId() {
            return id;
        }

        @Override
        public List<ResumeState> resumeStates() {
            return List.copyOf(resumeStates);
        }

        @Override
        public void checkpoint(ResumeState state) {
            checkpointCalls++;
            saved.removeIf(existing -> existing.getShardNo().equals(state.getShardNo()));
            saved.add(state);
            if (checkpointCalls >= cancelAfterCheckpoints) {
                throw new TaskCancelledException();
            }
        }

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void checkCancelled() {
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            return createArtifact(ai.chat2db.community.domain.api.model.task.TaskArtifactRole.OUTPUT,
                    outputDirectory, fileName, mediaType);
        }

        @Override
        public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName,
                String mediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }

    private static final class StorageStub implements TaskStorage {

        private final List<Task> tasks = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();
        private final List<ResumeState> states = new ArrayList<>();
        private Optional<ImportManifest> manifest = Optional.empty();
        private long sequence;

        @Override
        public Task create(Task task, TaskEvent createdEvent) {
            task.setId((long) (tasks.size() + 1));
            task.setStatus(TaskStatus.PENDING.name());
            tasks.add(task);
            createdEvent.setTaskId(task.getId());
            appendEvent(createdEvent);
            return task;
        }

        @Override
        public Optional<Task> get(Long taskId) {
            return tasks.stream().filter(task -> task.getId().equals(taskId)).findFirst();
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            return PageResponse.of(tasks, (long) tasks.size(), 1, 20);
        }

        @Override
        public boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
                TaskStatusPatch patch, TaskEvent lifecycleEvent) {
            return true;
        }

        @Override
        public boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
            return true;
        }

        @Override
        public TaskEvent appendEvent(TaskEvent event) {
            event.setSequence(++sequence);
            events.add(event);
            return event;
        }

        @Override
        public List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
            return events.stream().filter(event -> event.getSequence() > afterSequence).limit(limit).toList();
        }

        @Override
        public List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
            return events.stream()
                    .filter(event -> beforeSequence == null || event.getSequence() < beforeSequence)
                    .toList();
        }

        @Override
        public List<Task> listNonTerminalTasks() {
            return List.of();
        }

        @Override
        public boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
            return false;
        }

        @Override
        public List<TaskArtifact> listArtifacts(Long taskId) {
            return List.of();
        }

        @Override
        public void saveArtifact(Long taskId, TaskArtifact artifact) {
        }

        @Override
        public void deleteArtifact(Long taskId, String artifactId) {
        }

        @Override
        public List<Task> listResumableTasks() {
            return List.of();
        }

        @Override
        public synchronized void saveResumeState(Long taskId, ResumeState state) {
            states.add(state);
        }

        @Override
        public synchronized boolean compareAndSetResumeState(Long taskId, Integer shardNo, String expectedKind,
                ResumeState targetState) {
            for (int index = 0; index < states.size(); index++) {
                ResumeState current = states.get(index);
                if (java.util.Objects.equals(shardNo, current.getShardNo())
                        && java.util.Objects.equals(expectedKind, current.getKind())) {
                    states.set(index, targetState);
                    return true;
                }
            }
            return false;
        }

        @Override
        public synchronized List<ResumeState> listResumeStates(Long taskId) {
            return List.copyOf(states);
        }

        @Override
        public void clearResumeStates(Long taskId) {
            states.clear();
        }

        @Override
        public synchronized void saveImportManifest(Long taskId, ImportManifest importManifest) {
            if (manifest.isPresent() && !manifest.get().getManifestFingerprint()
                    .equals(importManifest.getManifestFingerprint())) {
                throw new IllegalStateException("manifest replacement");
            }
            manifest = Optional.of(importManifest);
        }

        @Override
        public synchronized Optional<ImportManifest> loadImportManifest(Long taskId) {
            return manifest;
        }
    }
}
