package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.plugin.h2.H2Plugin;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Production CSV parser/batcher with a stale local checkpoint and no target uniqueness constraint. */
class ImportReceiptRoundTripTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"CSV", "JSON", "XLSX", "XLS", "CSV_SKIP", "XLSX_SKIP"})
    void committedRowBeyondTheLocalCheckpointIsNotRewritten(String inputFormat) throws Exception {
        boolean skip = inputFormat.endsWith("_SKIP");
        String format = inputFormat.replace("_SKIP", "");
        String previousHome = System.getProperty("user.home");
        Path home = directory.resolve("home");
        System.setProperty("user.home", home.toString());
        assertTrue(Path.of(ConfigUtils.getBasePath()).toAbsolutePath().normalize().startsWith(home.toAbsolutePath()));
        IPlugin previous = Chat2DBContext.PLUGIN_MAP.put("H2", new H2Plugin());
        String url = "jdbc:h2:mem:receipt_roundtrip_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection observer = DriverManager.getConnection(url)) {
            try (Statement statement = observer.createStatement()) {
                statement.execute("CREATE TABLE TARGET_ROWS (ID INT CHECK(ID>0), NOTE VARCHAR(100))");
            }
            Path file = directory.resolve("rows." + format.toLowerCase(java.util.Locale.ROOT));
            StringBuilder content = new StringBuilder(format.equals("CSV") ? "ID,NOTE\n" : "[");
            for (int id = 1; id <= 50; id++) {
                if (format.equals("CSV")) content.append(skip && id == 3 ? "bad-number" : skip && id == 4 ? "-4" : id).append(",中文-").append(id).append('\n');
                else content.append(id == 1 ? "" : ",").append("{\"ID\":").append(id).append(",\"NOTE\":\"中文-").append(id).append("\"}");
            }
            if (format.equals("JSON")) content.append(']');
            if (format.equals("XLSX") || format.equals("XLS")) {
                try (org.apache.poi.ss.usermodel.Workbook book = format.equals("XLSX")
                        ? new org.apache.poi.xssf.usermodel.XSSFWorkbook() : new org.apache.poi.hssf.usermodel.HSSFWorkbook()) {
                    var sheet = book.createSheet("rows");
                    var header = sheet.createRow(0);
                    header.createCell(0).setCellValue("ID"); header.createCell(1).setCellValue("NOTE");
                    for (int id = 1; id <= 50; id++) {
                        var row = sheet.createRow(id);
                        if (skip && (id == 3 || id == 4)) row.createCell(0).setCellValue(id == 3 ? "bad-number" : "-4");
                        else row.createCell(0).setCellValue(id);
                        row.createCell(1).setCellValue("中文-" + id);
                    }
                    try (var output = Files.newOutputStream(file)) { book.write(output); }
                }
            } else Files.writeString(file, content);
            ImportTaskSpec spec = ImportTaskSpec.builder().taskType("DATA_FILE_IMPORT").format(format)
                    .sourceFile(file.toString()).mode("STANDARD")
                    .recoveryIdentity(UUID.randomUUID().toString()).recoveryProtocol(ImportRecoveryIdentity.DATABASE)
                    .target(TaskTargetSnapshot.builder().schemaName("PUBLIC").tableName("TARGET_ROWS").build()).build();
            Context context = new Context();
            context.rejectFile = directory.resolve("rejects-" + inputFormat + ".ndjson");
            if (skip) {
                var options = new ai.chat2db.community.domain.api.model.task.ImportOptions();
                options.setOnError("SKIP"); options.setMaxErrors(10); spec.setOptions(options);
                if (format.equals("CSV")) spec.setCsvOptions(new ai.chat2db.community.domain.api.model.task.CsvOptions());
                else spec.setExcelOptions(new ai.chat2db.community.domain.api.model.task.ExcelOptions());
            }
            ConnectInfo info = new ConnectInfo(); info.setDbType("H2"); info.setSchemaName("PUBLIC");
            info.setDriverConfig(new DriverConfig());
            Connection physical = DriverManager.getConnection(url);
            AtomicInteger commits = new AtomicInteger();
            info.setConnection((Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(physical, args);
                            if (method.getName().equals("commit") && commits.incrementAndGet() == 12) {
                                throw new SQLException("commit reply lost after durable write", "08006");
                            }
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    }));
            Chat2DBContext.putContext(info);
            TaskExecutionException unknown = assertThrows(TaskExecutionException.class, () -> ImportFactory.get(format).run(spec, context));
            assertEquals("IMPORT_COMMIT_OUTCOME_UNKNOWN", unknown.getCode());
            assertEquals(skip ? 10 : 12, count(observer));
            assertNotNull(context.checkpoint);
            assertTrue(context.checkpoint.getRowsDone() < 12, "local progress must lag the real committed row");
            assertNull(info.getConnection()); assertTrue(physical.isClosed());
            List<String> firstRejects = skip ? Files.readAllLines(context.rejectFile) : List.of();
            info.setConnection(DriverManager.getConnection(url));
            try {
                ImportFactory.get(format).run(spec, context);
                assertEquals(skip ? 48 : 50, count(observer));
                try (Statement statement = observer.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(DISTINCT ID),SUM(ID),SUM(CASE WHEN NOTE=CONCAT('中文-',ID) THEN 0 ELSE 1 END) FROM TARGET_ROWS")) {
                    assertTrue(rows.next()); assertEquals(skip ? 48 : 50, rows.getInt(1)); assertEquals(skip ? 1268 : 1275, rows.getInt(2)); assertEquals(0, rows.getInt(3));
                }
                if (skip) {
                    var rejects = Files.readAllLines(context.rejectFile);
                    assertEquals(2, rejects.size());
                    assertEquals(firstRejects, rejects, "recovery must reconstruct the same rejected rows and reasons");
                    assertTrue(rejects.stream().anyMatch(line -> line.contains("bad-number")));
                    assertTrue(rejects.stream().anyMatch(line -> line.contains("-4")));
                    try (var statement = observer.createStatement(); var rows = statement.executeQuery(
                            "SELECT COUNT(*) FROM PUBLIC.\"__chat2db_import_receipts_v1\" WHERE outcome='REJECTED'")) {
                        assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
                    }
                }
            } finally { info.getConnection().close(); }
        } finally {
            Chat2DBContext.removeContext();
            if (previous == null) Chat2DBContext.PLUGIN_MAP.remove("H2"); else Chat2DBContext.PLUGIN_MAP.put("H2", previous);
            System.setProperty("user.home", previousHome);
        }
    }

    private int count(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM TARGET_ROWS")) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }

    private static final class Context extends ImportReceiptStoreTest.Context {
        ResumeState checkpoint;
        Path rejectFile;
        @Override public ai.chat2db.community.domain.api.model.task.ArtifactDraft createArtifact(
                String role, String path, String name, String mediaType) {
            assertEquals("REJECT", role);
            return ai.chat2db.community.domain.api.model.task.ArtifactDraft.builder()
                    .role(role).temporaryFile(rejectFile.toFile()).targetFile(rejectFile.toFile()).mediaType(mediaType).build();
        }
        @Override public Long taskId() { return 17L; }
        @Override public void checkpoint(ResumeState state) { checkpoint = state; }
        @Override public List<ResumeState> resumeStates() { return checkpoint == null ? List.of() : List.of(checkpoint); }
    }
}
