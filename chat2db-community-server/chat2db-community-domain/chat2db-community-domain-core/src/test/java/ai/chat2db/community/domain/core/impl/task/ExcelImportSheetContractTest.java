package ai.chat2db.community.domain.core.impl.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ExcelOptions;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.impl.task.imports.excel.XLSXImporter;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Excel execution path has to honour the sheet and header the preview showed. Reading a fixed
 * first sheet is a data bug: the user picked a sheet, the preview showed it, the import wrote another.
 */
class ExcelImportSheetContractTest {

    private static final String DB_TYPE = "EXCEL_IMPORT_TEST";

    @TempDir
    Path tempDirectory;

    private java.sql.Connection connection;
    private IPlugin previousPlugin;
    private RecordingTaskStorage storage;

    @BeforeEach
    void setUp() throws Exception {
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
        connection = DriverManager.getConnection("jdbc:h2:mem:excel_import");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TARGET_ROWS (ID INT PRIMARY KEY, NAME VARCHAR(40))");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
        storage = new RecordingTaskStorage();
    }

    /** Only the four operations an import run performs are real; the rest must not be called. */
    private static final class RecordingTaskStorage implements TaskStorage {

        private final List<TaskEvent> events = new ArrayList<>();
        private final List<ResumeState> resumeStates = new ArrayList<>();

        @Override
        public synchronized Task create(Task task, TaskEvent createdEvent) {
            task.setId(1L);
            events.add(createdEvent);
            return task;
        }

        @Override
        public Optional<Task> get(Long taskId) {
            return Optional.empty();
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            throw new UnsupportedOperationException("not used by an import run");
        }

        @Override
        public boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
                TaskStatusPatch patch, TaskEvent lifecycleEvent) {
            events.add(lifecycleEvent);
            return true;
        }

        @Override
        public boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
            return true;
        }

        @Override
        public synchronized TaskEvent appendEvent(TaskEvent event) {
            events.add(event);
            return event;
        }

        @Override
        public synchronized List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
            return List.copyOf(events);
        }

        @Override
        public List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
            return List.copyOf(events);
        }

        @Override
        public List<Task> listNonTerminalTasks() {
            return List.of();
        }

        @Override
        public boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
            throw new UnsupportedOperationException("not used by an import run");
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
            resumeStates.add(state);
        }

        @Override
        public synchronized List<ResumeState> listResumeStates(Long taskId) {
            return List.copyOf(resumeStates);
        }

        @Override
        public void clearResumeStates(Long taskId) {
            resumeStates.clear();
        }
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // The connection only exists for this test.
        }
    }

    @Test
    void theConfiguredSheetIsImportedAndNotTheFirstOne() throws Exception {
        Path workbook = tempDirectory.resolve("book.xlsx");
        writeTwoSheetWorkbook(workbook);

        Long taskId = storage.create(Task.builder().type("DATA_FILE_IMPORT").name("import")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("TARGET_ROWS").build())
                .build(),
                TaskEvent.builder().level("INFO").code("TASK_CREATED").message("created").build()).getId();
        TaskExecutionContextImpl context = new TaskExecutionContextImpl(taskId, new RunningTask(taskId, () -> { }),
                storage, new ArtifactServiceImpl());

        new XLSXImporter().run(ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(workbook.toString())
                .format("XLSX")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("TARGET_ROWS").build())
                .excelOptions(secondSheet())
                .options(ai.chat2db.community.domain.api.model.task.ImportOptions.builder()
                        .onError("ABORT").maxErrors(5).build())
                .columnMappings(List.of(
                        new ImportColumnMapping("ROW_ID", "ID"),
                        new ImportColumnMapping("ROW_NAME", "NAME")))
                .build(), context);

        assertEquals(List.of(2, 3), ids(), "only the rows of the selected sheet may be imported");
    }

    private static ExcelOptions secondSheet() {
        ExcelOptions options = new ExcelOptions();
        options.setSheetIndex(1);
        options.setHasHeader(Boolean.TRUE);
        options.setHeaderRow(1);
        options.setDataStartRow(2);
        return options.validate();
    }

    private List<Integer> ids() throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT ID FROM TARGET_ROWS ORDER BY ID")) {
            List<Integer> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
            return ids;
        }
    }

    /** Sheet 0 holds rows that must never be imported; sheet 1 holds the rows the user selected. */
    private void writeTwoSheetWorkbook(Path workbook) {
        // One entry per column, each a single head row.
        List<List<String>> head = List.of(List.of("ROW_ID"), List.of("ROW_NAME"));
        try (ExcelWriter writer = EasyExcel.write(workbook.toFile()).build()) {
            writer.write(List.of(List.of(90, "wrong sheet")),
                    EasyExcel.writerSheet(0, "first").head(head).build());
            writer.write(List.of(List.of(2, "ok"), List.of(3, "fine")),
                    EasyExcel.writerSheet(1, "second").head(head).build());
        }
    }
}
