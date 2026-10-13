package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import ai.chat2db.community.domain.api.model.task.ExcelOptions;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.core.impl.task.imports.reader.ExcelImportReader;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the XLSX execution path with no client options, so the reader defaults must reach the row
 * builder for text values to be normalized like the preview shows.
 */
class XLSXImporterExecutionTest {

    private static final String TEST_DB_TYPE = "XLSX_IMPORT_EXECUTION_TEST";

    private IPlugin previousPlugin;

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin());
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:xlsx_import_execution;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS shop CASCADE");
            statement.execute("CREATE SCHEMA shop");
            statement.execute("CREATE TABLE shop.excel_rows (id INT PRIMARY KEY, event_date DATE, note VARCHAR(50))");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(11L);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDatabaseName("shop");
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void excelImportUsesReaderDefaultsWhenTheClientSendsNoOptions(@TempDir Path directory) throws Exception {
        Path workbook = directory.resolve("rows.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("event_date");
            header.createCell(2).setCellValue("note");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(1);
            row.createCell(1).setCellValue("2026-9-6");
            try (var out = Files.newOutputStream(workbook)) {
                book.write(out);
            }
        }
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(workbook.toString())
                .target(TaskTargetSnapshot.builder().tableName("excel_rows").build())
                .build();
        TableColumn id = TableColumn.builder().name("id").columnType("INTEGER").dataType(Types.INTEGER).build();
        TableColumn eventDate = TableColumn.builder().name("event_date").columnType("DATE")
                .dataType(Types.DATE).build();
        TableColumn note = TableColumn.builder().name("note").columnType("VARCHAR")
                .dataType(Types.VARCHAR).build();

        new XLSXImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(id, eventDate, note));

        assertNotNull(spec.getExcelOptions(), "the reader defaults must be visible to the row builder");
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id, event_date, note FROM shop.excel_rows")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals("2026-09-06", rows.getString(2));
            assertEquals(null, rows.getString(3));
        }
    }

    @Test
    void headerlessXlsxImportsFirstDataRow(@TempDir Path directory) throws Exception {
        verifySelection(directory, false, true);
    }

    @Test
    void headerlessXlsImportsFirstDataRow(@TempDir Path directory) throws Exception {
        verifySelection(directory, true, true);
    }

    @Test
    void xlsxHonorsHeaderRowAndSelectedRowColumnRange(@TempDir Path directory) throws Exception {
        verifySelection(directory, false, false);
    }

    @Test
    void xlsHonorsHeaderRowAndSelectedRowColumnRange(@TempDir Path directory) throws Exception {
        verifySelection(directory, true, false);
    }

    private void verifySelection(Path directory, boolean xls, boolean headerless) throws Exception {
        Path file = directory.resolve(xls ? "selected.xls" : "selected.xlsx");
        ExcelOptions options = new ExcelOptions();
        options.setHasHeader(!headerless);
        options.setColumnRange("B:D");
        if (!headerless) {
            options.setHeaderRow(2);
            options.setDataStartRow(4);
            options.setDataEndRow(5);
        }
        try (Workbook book = xls ? new HSSFWorkbook() : new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Data");
            if (!headerless) {
                sheet.createRow(0).createCell(0).setCellValue("Instructions, not a header");
                Row header = sheet.createRow(1);
                header.createCell(0).setCellValue("ignored");
                header.createCell(1).setCellValue("id");
                header.createCell(2).setCellValue("event_date");
                header.createCell(3).setCellValue("note");
            }
            int first = headerless ? 0 : 2;
            int count = headerless ? 2 : 4;
            for (int i = 0; i < count; i++) {
                Row row = sheet.createRow(first + i);
                row.createCell(0).setCellValue(900 + i);
                row.createCell(1).setCellValue(10 + i);
                row.createCell(2).setCellValue("2026-9-6");
                if (i % 2 == 1) row.createCell(3).setCellValue("kept");
                row.createCell(4).setCellValue("outside range");
            }
            try (var out = Files.newOutputStream(file)) { book.write(out); }
        }
        List<Integer> previewIds = new ArrayList<>();
        ExcelImportReader.read(file.toFile(), options, 100, 3, head -> { },
                (row, line) -> previewIds.add(Integer.valueOf(row.get(0).display())), () -> { });
        List<Integer> expected = headerless ? List.of(10, 11) : List.of(11, 12);
        assertEquals(expected, previewIds, "hand-selected preview rows");
        ImportTaskSpec spec = ImportTaskSpec.builder().sourceFile(file.toString())
                .target(TaskTargetSnapshot.builder().tableName("excel_rows").build())
                .excelOptions(options)
                .columnMappings(headerless ? List.of(new ImportColumnMapping("column_1", "id"),
                        new ImportColumnMapping("column_2", "event_date"),
                        new ImportColumnMapping("column_3", "note")) : null).build();
        List<TableColumn> columns = List.of(
                TableColumn.builder().name("id").columnType("INTEGER").dataType(Types.INTEGER).build(),
                TableColumn.builder().name("event_date").columnType("DATE").dataType(Types.DATE).build(),
                TableColumn.builder().name("note").columnType("VARCHAR").dataType(Types.VARCHAR).build());
        BaseExcelImporter importer = xls ? new XLSImporter() : new XLSXImporter();
        NoOpTaskExecutionContext context = new NoOpTaskExecutionContext();
        importer.doImportData(spec, context, columns);
        assertEquals(2L, context.checkpoints.get(context.checkpoints.size() - 1).getRowsDone(),
                "watermark counts selected data rows, not physical worksheet lines");
        List<Integer> actual = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id, event_date, note FROM shop.excel_rows ORDER BY id")) {
            while (rows.next()) {
                int id = rows.getInt(1);
                actual.add(id);
                assertEquals("2026-09-06", rows.getString(2));
                assertEquals(id % 2 == 1 ? "kept" : null, rows.getString(3));
            }
        }
        assertEquals(expected, actual, "execution must persist exactly the manually selected rows");
    }

    @Test
    void nativeXlsxCellsKeepDatesNumbersAndNulls(@TempDir Path directory) throws Exception {
        verifyNativeCells(directory, false);
    }

    @Test
    void nativeXlsCellsKeepDatesNumbersAndNulls(@TempDir Path directory) throws Exception {
        verifyNativeCells(directory, true);
    }

    private void verifyNativeCells(Path directory, boolean xls) throws Exception {
        Path file = directory.resolve(xls ? "native.xls" : "native.xlsx");
        try (Workbook book = xls ? new HSSFWorkbook() : new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("event_date");
            header.createCell(2).setCellValue("note");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(42);
            var numberFormat = book.createCellStyle();
            numberFormat.setDataFormat(book.createDataFormat().getFormat("000000"));
            row.getCell(0).setCellStyle(numberFormat);
            row.createCell(1).setCellValue(java.time.LocalDateTime.of(2026, 9, 6, 13, 14));
            var dateFormat = book.createCellStyle();
            dateFormat.setDataFormat(book.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
            row.getCell(1).setCellStyle(dateFormat);
            try (var out = Files.newOutputStream(file)) { book.write(out); }
        }
        ExcelOptions options = new ExcelOptions();
        options.setDateOrder("DMY");
        options.setDateDelimiter("/");
        options.setYearDelimiter("/");
        ImportTaskSpec spec = ImportTaskSpec.builder().sourceFile(file.toString())
                .target(TaskTargetSnapshot.builder().tableName("excel_rows").build()).excelOptions(options).build();
        BaseExcelImporter importer = xls ? new XLSImporter() : new XLSXImporter();
        importer.doImportData(spec, new NoOpTaskExecutionContext(), List.of(
                TableColumn.builder().name("id").columnType("INTEGER").dataType(Types.INTEGER).build(),
                TableColumn.builder().name("event_date").columnType("DATE").dataType(Types.DATE).build(),
                TableColumn.builder().name("note").columnType("VARCHAR").dataType(Types.VARCHAR).build()));
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id, event_date, note FROM shop.excel_rows")) {
            assertTrue(rows.next());
            assertEquals(42, rows.getInt(1));
            assertEquals("2026-09-06", rows.getString(2));
            assertEquals(null, rows.getString(3));
            assertTrue(!rows.next());
        }
    }

    @Test
    void xlsxTextFormatsAndEmptyCellOptionsAreApplied(@TempDir Path directory) throws Exception {
        verifyTextFormats(directory, false);
    }

    @Test
    void xlsTextFormatsAndEmptyCellOptionsAreApplied(@TempDir Path directory) throws Exception {
        verifyTextFormats(directory, true);
    }

    private void verifyTextFormats(Path directory, boolean xls) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE shop.excel_rows ADD amount DECIMAL(10,2)");
            statement.execute("ALTER TABLE shop.excel_rows ADD flag BOOLEAN");
        }
        for (boolean emptyAsNull : List.of(false, true)) {
            Path file = directory.resolve("formats-" + emptyAsNull + (xls ? ".xls" : ".xlsx"));
            try (Workbook book = xls ? new HSSFWorkbook() : new XSSFWorkbook()) {
                Sheet sheet = book.createSheet("Data");
                Row header = sheet.createRow(0);
                String[] names = {"note", "amount", "flag", "event_date", "id"};
                for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
                Row text = sheet.createRow(1);
                text.createCell(1).setCellValue("12,34");
                text.createCell(2).setCellValue(true);
                text.createCell(3).setCellValue("06/09/2026");
                text.createCell(4).setCellValue(emptyAsNull ? 52 : 51);
                Row nativeRow = sheet.createRow(2);
                nativeRow.createCell(0).setCellValue("native");
                nativeRow.createCell(1).setCellValue(56.78);
                nativeRow.createCell(2).setCellValue(false);
                nativeRow.createCell(3).setCellValue(java.time.LocalDateTime.of(2026, 9, 7, 13, 14));
                var style = book.createCellStyle();
                style.setDataFormat(book.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
                nativeRow.getCell(3).setCellStyle(style);
                nativeRow.createCell(4).setCellValue(emptyAsNull ? 62 : 61);
                try (var out = Files.newOutputStream(file)) { book.write(out); }
            }
            ExcelOptions options = new ExcelOptions();
            options.setDateOrder("DMY"); options.setDateDelimiter("/"); options.setYearDelimiter("/");
            options.setDecimalSymbol(","); options.setEmptyAsNull(emptyAsNull);
            ImportTaskSpec spec = ImportTaskSpec.builder().sourceFile(file.toString())
                    .excelOptions(options).target(TaskTargetSnapshot.builder().tableName("excel_rows").build())
                    .columnMappings(List.of(new ImportColumnMapping("id", "id"),
                            new ImportColumnMapping("event_date", "event_date"),
                            new ImportColumnMapping("note", "note"), new ImportColumnMapping("amount", "amount"),
                            new ImportColumnMapping("flag", "flag"))).build();
            (xls ? new XLSImporter() : new XLSXImporter()).doImportData(spec,
                    new NoOpTaskExecutionContext(), formatColumns());
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT event_date, note, amount, flag FROM shop.excel_rows WHERE id="
                            + (emptyAsNull ? 52 : 51))) {
                assertTrue(rows.next());
                assertEquals("2026-09-06", rows.getString(1));
                assertEquals(emptyAsNull ? null : "", rows.getString(2));
                assertEquals(new java.math.BigDecimal("12.34"), rows.getBigDecimal(3));
                assertTrue(rows.getBoolean(4));
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT event_date, amount, flag FROM shop.excel_rows WHERE id="
                            + (emptyAsNull ? 62 : 61))) {
                assertTrue(rows.next()); assertEquals("2026-09-07", rows.getString(1));
                assertEquals(new java.math.BigDecimal("56.78"), rows.getBigDecimal(2));
                assertEquals(false, rows.getBoolean(3));
            }
        }
    }

    private List<TableColumn> formatColumns() {
        return List.of(TableColumn.builder().name("id").columnType("INTEGER").dataType(Types.INTEGER).build(),
                TableColumn.builder().name("event_date").columnType("DATE").dataType(Types.DATE).build(),
                TableColumn.builder().name("note").columnType("VARCHAR").dataType(Types.VARCHAR).build(),
                TableColumn.builder().name("amount").columnType("DECIMAL").dataType(Types.DECIMAL).build(),
                TableColumn.builder().name("flag").columnType("BOOLEAN").dataType(Types.BOOLEAN).build());
    }

    @Test
    void xlsxEmptyCellsRespectEmptyAsNull(@TempDir Path directory) throws Exception {
        verifyEmptyCells(directory, false);
    }

    @Test
    void xlsEmptyCellsRespectEmptyAsNull(@TempDir Path directory) throws Exception {
        verifyEmptyCells(directory, true);
    }

    private void verifyEmptyCells(Path directory, boolean xls) throws Exception {
        for (boolean emptyAsNull : List.of(false, true)) {
            Path file = directory.resolve("empty-" + emptyAsNull + (xls ? ".xls" : ".xlsx"));
            try (Workbook book = xls ? new HSSFWorkbook() : new XSSFWorkbook()) {
                Sheet sheet = book.createSheet("Data");
                Row header = sheet.createRow(0);
                header.createCell(0).setCellValue("id"); header.createCell(1).setCellValue("note");
                sheet.createRow(1).createCell(0).setCellValue(emptyAsNull ? 72 : 71);
                try (var out = Files.newOutputStream(file)) { book.write(out); }
            }
            ExcelOptions options = new ExcelOptions(); options.setEmptyAsNull(emptyAsNull);
            ImportTaskSpec spec = ImportTaskSpec.builder().sourceFile(file.toString()).excelOptions(options)
                    .target(TaskTargetSnapshot.builder().tableName("excel_rows").build()).build();
            (xls ? new XLSImporter() : new XLSXImporter()).doImportData(spec, new NoOpTaskExecutionContext(),
                    List.of(TableColumn.builder().name("id").columnType("INTEGER").dataType(Types.INTEGER).build(),
                            TableColumn.builder().name("note").columnType("VARCHAR").dataType(Types.VARCHAR).build()));
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT note FROM shop.excel_rows WHERE id=" + (emptyAsNull ? 72 : 71))) {
                assertTrue(rows.next()); assertEquals(emptyAsNull ? null : "", rows.getString(1));
            }
        }
    }

    @Test
    void xlsxMalformedTextSkipKeepsGoodRows(@TempDir Path directory) throws Exception {
        verifyMalformedText(directory, false, true);
    }

    @Test
    void xlsMalformedTextSkipKeepsGoodRows(@TempDir Path directory) throws Exception {
        verifyMalformedText(directory, true, true);
    }

    @Test
    void xlsxMalformedTextAbortPreservesCommittedPrefix(@TempDir Path directory) throws Exception {
        verifyMalformedText(directory, false, false);
    }

    @Test
    void xlsMalformedTextAbortPreservesCommittedPrefix(@TempDir Path directory) throws Exception {
        verifyMalformedText(directory, true, false);
    }

    private void verifyMalformedText(Path directory, boolean xls, boolean skip) throws Exception {
        Path file = directory.resolve("malformed" + (xls ? ".xls" : ".xlsx"));
        try (Workbook book = xls ? new HSSFWorkbook() : new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("id"); header.createCell(1).setCellValue("event_date");
            for (int i = 1; i <= 502; i++) {
                Row row = sheet.createRow(i); row.createCell(0).setCellValue(i);
                row.createCell(1).setCellValue(i == 501 ? "not-a-date" : "06/09/2026");
            }
            try (var out = Files.newOutputStream(file)) { book.write(out); }
        }
        ExcelOptions options = new ExcelOptions(); options.setDateOrder("DMY");
        options.setDateDelimiter("/"); options.setYearDelimiter("/");
        ImportTaskSpec spec = ImportTaskSpec.builder().sourceFile(file.toString()).excelOptions(options)
                .format(xls ? "XLS" : "XLSX")
                .options(ImportOptions.builder().onError(skip ? "SKIP" : "ABORT").maxErrors(5).build())
                .target(TaskTargetSnapshot.builder().tableName("excel_rows").build()).build();
        BaseExcelImporter importer = xls ? new XLSImporter() : new XLSXImporter();
        NoOpTaskExecutionContext context = new NoOpTaskExecutionContext();
        List<TableColumn> columns = List.of(
                TableColumn.builder().name("id").columnType("INTEGER").dataType(Types.INTEGER).build(),
                TableColumn.builder().name("event_date").columnType("DATE").dataType(Types.DATE).build());
        if (skip) importer.doImportData(spec, context, columns);
        else assertThrows(RuntimeException.class, () -> importer.doImportData(spec, context, columns));
        List<Integer> actual = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id, event_date FROM shop.excel_rows ORDER BY id")) {
            while (rows.next()) {
                actual.add(rows.getInt(1)); assertEquals("2026-09-06", rows.getString(2));
            }
        }
        assertEquals(skip ? 501 : 500, actual.size(), "successful batch prefix must survive");
        for (int i = 0; i < 500; i++) assertEquals(i + 1, actual.get(i));
        if (skip) {
            assertEquals(502, actual.get(500));
            String rejects = Files.readString(directory.resolve(file.getFileName() + ".rejects.ndjson"));
            assertTrue(rejects.contains("not-a-date"));
            assertTrue(rejects.contains("\"row\":501"));
        }
    }

    private IPlugin plugin() {
        DBConfig config = new DBConfig();
        config.setDbType(TEST_DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        IDbMetaData metaData = new DefaultMetaService();
        return new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return metaData;
            }
        };
    }

    private static final class NoOpTaskExecutionContext implements TaskExecutionContext {
        private final List<ResumeState> checkpoints = new ArrayList<>();

        @Override
        public void checkpoint(ResumeState state) { checkpoints.add(state); }


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
            throw new UnsupportedOperationException();
        }

        @Override
        public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName, String mediaType) {
            return ArtifactDraft.builder().role(role)
                    .temporaryFile(Path.of(outputDirectory, fileName).toFile())
                    .targetFile(Path.of(outputDirectory, fileName).toFile()).mediaType(mediaType).build();
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
}
