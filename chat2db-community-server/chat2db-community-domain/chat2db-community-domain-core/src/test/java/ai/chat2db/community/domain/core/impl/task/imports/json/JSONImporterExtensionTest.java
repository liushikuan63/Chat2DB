package ai.chat2db.community.domain.core.impl.task.imports.json;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.ParamBusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JSONImporterExtensionTest {

    private static final String TEST_DB_TYPE = "JSON_IMPORT_POLICY_TEST";

    private IPlugin previousPlugin;

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin());
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:json_import_extension;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS shop CASCADE");
            statement.execute("CREATE SCHEMA shop");
            statement.execute("CREATE TABLE shop.orders (id INT PRIMARY KEY, note VARCHAR(255))");
            statement.execute("CREATE TABLE shop.nullable_orders (id INT)");
            statement.execute("CREATE TABLE shop.typed_rows (id INT, name VARCHAR(255), active BOOLEAN, note VARCHAR(255))");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(7L);
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
    void jsonImportExecutesGeneratedStatementsThroughTheNewTaskSqlExecutor(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("orders.json");
        Files.writeString(input, "[{\"id\":1},{\"id\":2}]");
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .build();
        TableColumn id = TableColumn.builder().name("id").columnType("INTEGER").build();

        new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(id));

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM shop.orders")) {
            resultSet.next();
            assertEquals(2, resultSet.getInt(1));
        }
    }

    @Test
    void failingJsonRowPreservesThePreviouslyExecutedPrefix(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("duplicate.json");
        Files.writeString(input, "[{\"id\":1},{\"id\":1},{\"id\":2}]");
        ImportTaskSpec spec = ImportTaskSpec.builder().sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("orders").build()).build();
        TableColumn id = TableColumn.builder().name("id").columnType("INTEGER").build();
        assertThrows(ai.chat2db.community.domain.api.model.task.TaskExecutionException.class,
                () -> new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(id)));
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id FROM shop.orders ORDER BY id")) {
            java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
            while (rows.next()) ids.add(rows.getInt(1));
            assertEquals(List.of(1), ids);
        }
    }

    @Test
    void explicitJsonNullIsImportedAsSqlNull(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("orders.json");
        Files.writeString(input, "[{\"id\":1,\"note\":null}]");
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .build();
        TableColumn id = TableColumn.builder().name("id").columnType("INTEGER").build();
        TableColumn note = TableColumn.builder().name("note").columnType("VARCHAR").build();

        new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(id, note));

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT note FROM shop.orders WHERE id = 1")) {
            resultSet.next();
            assertNull(resultSet.getString(1));
        }
    }

    @Test
    void nonObjectArrayRecordFailsWithoutInsertingRows(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("orders.json");
        Files.writeString(input, "[1]");
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("nullable_orders").build())
                .build();
        TableColumn id = TableColumn.builder().name("id").columnType("INTEGER").build();

        assertThrows(BusinessException.class,
                () -> new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(id)));

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM shop.nullable_orders")) {
            resultSet.next();
            assertEquals(0, resultSet.getInt(1));
        }
    }

    @Test
    void caseAliasesInOneRecordAreRejectedBeforeWriting(@TempDir Path directory) throws Exception {
        assertCollision(directory, "[{\"Name\":\"A\",\"name\":\"B\"}]", null,
                List.of(column("name", "VARCHAR")), "Name", "name", "row 1");
    }

    @Test
    void whitespaceAliasesInLaterRecordReportItsRowBeforeWriting(@TempDir Path directory) throws Exception {
        assertCollision(directory, "[{\"name\":\"first\"},{\"name\":\"A\",\" name \" :\"B\"}]", null,
                List.of(column("name", "VARCHAR")), "name", " name ", "row 2");
    }

    @Test
    void normalizedMappingSourcesAreRejectedBeforeWriting(@TempDir Path directory) throws Exception {
        assertCollision(directory, "[{\"Name\":\"A\",\"name\":\"B\"}]",
                List.of(new ImportColumnMapping("Name", "name"), new ImportColumnMapping(" name ", "note")),
                List.of(column("name", "VARCHAR"), column("note", "VARCHAR")), "Name", " name ", "header");
    }

    @Test
    void normalizedTargetHeadersAreRejectedBeforeWriting(@TempDir Path directory) throws Exception {
        assertCollision(directory, "[{\"Name\":\"A\"}]", null,
                List.of(column("Name", "VARCHAR"), column("name", "VARCHAR")), "Name", "name", "header");
    }

    @Test
    void oneAliasKeepsNumbersBooleansAndNullNative(@TempDir Path directory) throws Exception {
        ImportTaskSpec spec = jsonSpec(directory, "[{\"ID\":7,\" Name \" :\"A\",\"active\":true,\"note\":null}]");
        new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(
                column("id", "INTEGER"), column("name", "VARCHAR"), column("active", "BOOLEAN"), column("note", "VARCHAR")));
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id, name, active, note FROM shop.typed_rows")) {
            assertTrue(rows.next());
            assertEquals(7, rows.getInt(1));
            assertEquals("A", rows.getString(2));
            assertTrue(rows.getBoolean(3));
            assertNull(rows.getString(4));
        }
    }

    @Test
    void unknownKeyStillReportsItsNameAndRowWithoutWriting(@TempDir Path directory) throws Exception {
        ImportTaskSpec spec = jsonSpec(directory, "[{\"Nickname\":\"A\"}]");
        ParamBusinessException failure = assertThrows(ParamBusinessException.class,
                () -> new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(column("name", "VARCHAR"))));
        assertEquals("import.json.unknownColumns[Nickname]@1", failure.getArgs()[0]);
        assertNoTypedRows();
    }

    @Test
    void collisionAfterExecutedBatchPreservesCommittedPrefixAndRejectsOnlyTheAmbiguousRecord(
            @TempDir Path directory) throws Exception {
        StringBuilder input = new StringBuilder("[");
        for (int id = 1; id <= 1000; id++) {
            if (id > 1) input.append(',');
            input.append("{\"id\":").append(id).append(",\"name\":\"row-").append(id).append("\"}");
        }
        input.append(",{\"id\":1001,\"Name\":\"A\",\"name\":\"B\"}]");
        ImportTaskSpec spec = jsonSpec(directory, input.toString());

        ParamBusinessException failure = assertThrows(ParamBusinessException.class,
                () -> new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(),
                        List.of(column("id", "INTEGER"), column("name", "VARCHAR"))));
        assertTrue(String.valueOf(failure.getArgs()[0]).contains("row 1001"));
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*), COUNT(DISTINCT id), SUM(id) FROM shop.typed_rows")) {
            assertTrue(rows.next());
            assertEquals(1000, rows.getInt(1));
            assertEquals(1000, rows.getInt(2));
            assertEquals(500500, rows.getLong(3));
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT name FROM shop.typed_rows WHERE id = 1000")) {
            assertTrue(rows.next());
            assertEquals("row-1000", rows.getString(1));
        }
    }

    private void assertCollision(Path directory, String json, List<ImportColumnMapping> mappings,
            List<TableColumn> columns, String first, String second, String location) throws Exception {
        ImportTaskSpec spec = jsonSpec(directory, json);
        spec.setColumnMappings(mappings);
        ParamBusinessException failure = assertThrows(ParamBusinessException.class,
                () -> new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(), columns));
        String detail = String.valueOf(failure.getArgs()[0]);
        assertTrue(detail.contains("'" + first + "'"), detail);
        assertTrue(detail.contains("'" + second + "'"), detail);
        assertTrue(detail.contains(location), detail);
        assertNoTypedRows();
    }

    private void assertNoTypedRows() throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM shop.typed_rows")) {
            rows.next();
            assertEquals(0, rows.getInt(1));
        }
    }

    private ImportTaskSpec jsonSpec(Path directory, String json) throws Exception {
        Path input = directory.resolve("typed.json");
        Files.writeString(input, json);
        return ImportTaskSpec.builder().sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("typed_rows").build()).build();
    }

    private static TableColumn column(String name, String type) {
        return TableColumn.builder().name(name).columnType(type).build();
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
        public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName, String mediaType) {
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
}
