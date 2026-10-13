package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportRollbackOptions;
import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportStagingPolicy;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportValidationOptions;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class RealDialectStagingITSupport {
    @TempDir Path tempDirectory;
    private Connection keeper;
    private Connection opened;
    private String database;
    private String schema;
    private String jdbcUrl;
    private String dbType;
    private ai.chat2db.community.domain.core.impl.task.RequiredDatabaseFixture fixture;
    private IPlugin previousPlugin;
    private int sourceSequence;
    protected abstract boolean postgres();

    @BeforeEach
    void setUp() throws Exception {
        fixture = ai.chat2db.community.domain.core.impl.task.RequiredDatabaseFixture.load(
                postgres() ? "POSTGRES" : "MARIADB", postgres() ? 5432 : 3306);
        database = fixture.database();
        schema = postgres() ? "public" : null;
        dbType = postgres() ? "POSTGRESQL" : "MARIADB";
        String base = "jdbc:" + (postgres() ? "postgresql" : "mysql") + "://" + fixture.host()
                + ":" + fixture.port() + "/";
        String suffix = postgres() ? "?connectTimeout=5&socketTimeout=120"
                : "?allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=5000&socketTimeout=120000";
        try (Connection admin = DriverManager.getConnection(base + (postgres() ? "postgres" : "") + suffix,
                fixture.user(), fixture.password()); Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database);
            statement.execute("CREATE DATABASE " + database);
        }
        jdbcUrl = base + database + suffix;
        keeper = open();
        String product = keeper.getMetaData().getDatabaseProductName();
        try (Statement statement = keeper.createStatement(); ResultSet result = statement.executeQuery("SELECT VERSION()")) {
            assertTrue(result.next());
            String version = result.getString(1);
            assertTrue(postgres() ? product.equals("PostgreSQL") : version.contains("MariaDB"),
                    "The test must use its real declared dialect");
            System.out.println("REAL_FIXTURE " + dbType + " " + version);
        }
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(dbType, postgres()
                ? new ai.chat2db.plugin.postgresql.PostgreSQLPlugin() : new ai.chat2db.plugin.mariadb.MariaDBPlugin());
        ConnectInfo info = new ConnectInfo();
        info.setDbType(dbType); info.setDatabaseName(database); info.setSchemaName(schema);
        info.setConnection(keeper);
        Chat2DBContext.putContext(info);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, fixture.user(), fixture.password());
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (dbType != null) {
            if (previousPlugin == null) Chat2DBContext.PLUGIN_MAP.remove(dbType);
            else Chat2DBContext.PLUGIN_MAP.put(dbType, previousPlugin);
        }
        if (opened != null && !opened.isClosed()) opened.close();
        if (keeper != null) keeper.close();
        // The runner destroys the entire dedicated container; no unrelated service is eligible.
    }

    @Test
    void publishesDependencyDagWithRealForeignKeysAndClosesStagingSession() throws Exception {
        FixturePlan plan = parentAndChild(false);
        RecordingContext context = new RecordingContext(701L, tempDirectory.resolve("dag"));
        importer(false).execute(plan.spec, context, plan.manifest);
        assertEquals(2, scalar("SELECT COUNT(*) FROM parents"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM children c JOIN parents p ON p.id=c.parent_id"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM parents WHERE id=1 AND name='alpha'"));
        assertEquals("COMMITTED", context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT)
                .getJSONObject("transaction").getString("outcome"));
        assertClosedAndClean();
        assertThrows(SQLException.class, () -> executeSql("INSERT INTO children VALUES (3,999)"));
    }

    @Test
    void rejectsOrphansWithoutPublishingEitherTable() throws Exception {
        FixturePlan plan = parentAndChild(true);
        RecordingContext context = new RecordingContext(701L, tempDirectory.resolve("orphan"));
        assertThrows(TaskExecutionException.class, () -> importer(false).execute(plan.spec, context, plan.manifest));
        assertEquals(0, scalar("SELECT COUNT(*) FROM parents"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM children"));
        assertClosedAndClean();
    }

    @Test
    void rehearsalRollsBackActualTargetRowsAndPreservesExistingValues() throws Exception {
        FixturePlan plan = parentAndChild(false);
        executeSql("INSERT INTO parents VALUES(999,'existing')");
        plan.spec.setRollbackOptions(ImportRollbackOptions.builder().rehearsal(true).build());
        plan.spec.setPerformanceSamplePercent(100);
        RecordingContext context = new RecordingContext(701L, tempDirectory.resolve("rollback"));
        importer(false).execute(plan.spec, context, plan.manifest);
        assertEquals(1, scalar("SELECT COUNT(*) FROM parents"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM parents WHERE id=999 AND name='existing'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM children"));
        JSONObject transaction = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT).getJSONObject("transaction");
        assertEquals("REHEARSAL_ROLLED_BACK", transaction.getString("outcome"));
        assertTrue(transaction.getBooleanValue("rollbackVerified"));
        assertClosedAndClean();
    }

    @Test
    void cancellationAtPublicationRollsBackAndCleansDedicatedConnection() throws Exception {
        FixturePlan plan = parentAndChild(false);
        RecordingContext context = new RecordingContext(701L, tempDirectory.resolve("cancel"));
        context.cancelAtPublication = true;
        assertThrows(ai.chat2db.community.domain.api.model.task.TaskCancelledException.class,
                () -> importer(false).execute(plan.spec, context, plan.manifest));
        assertEquals(0, scalar("SELECT COUNT(*) FROM parents"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM children"));
        assertClosedAndClean();
    }

    @Test
    void lostCommitAcknowledgementRetainsRealCommittedRowsAndReportsUnknown() throws Exception {
        FixturePlan plan = parentAndChild(false);
        RecordingContext context = new RecordingContext(701L, tempDirectory.resolve("unknown"));
        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer(true).execute(plan.spec, context, plan.manifest));
        assertTrue(failure.getMessage().contains("commit outcome is unknown"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM parents"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM children"));
        assertEquals("COMMIT_UNKNOWN", context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT)
                .getJSONObject("transaction").getString("outcome"));
        assertClosedAndClean();
    }

    @Test
    void probesRealTransactionalDdlSemantics() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("CREATE TABLE ddl_probe (id INTEGER)");
            statement.execute("INSERT INTO ddl_probe VALUES(1)");
            connection.rollback();
            if (postgres()) {
                assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM ddl_probe"));
            } else {
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM ddl_probe")) {
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                }
            }
        }
    }

    private StagingManifestImporter importer(boolean loseCommitAcknowledgement) {
        return new StagingManifestImporter(ignored -> {
            opened = open();
            if (!loseCommitAcknowledgement) return opened;
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(opened, args);
                            if (method.getName().equals("commit")) throw new SQLException("simulated lost acknowledgement after real commit");
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        });
    }

    private FixturePlan parentAndChild(boolean orphan) throws Exception {
        executeSql("CREATE TABLE parents (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        executeSql("CREATE TABLE children (id BIGINT PRIMARY KEY, parent_id BIGINT NOT NULL, "
                + "CONSTRAINT child_parent FOREIGN KEY(parent_id) REFERENCES parents(id))");
        ImportTableSource parents = tableSource("parents", source("parents", "id,name\n1,alpha\n2,beta\n"), null);
        ImportTableSource children = tableSource("children", source("children", "id,parent_id\n1,1\n2,"
                + (orphan ? "999" : "2") + "\n"), null);
        ImportManifest manifest = manifest(701L, dagPlan(List.of(List.of(key("parents")), List.of(key("children")))),
                List.of(dependency("parents", "id", "children", "parent_id", "child_parent")),
                shards(parents, 0, Long.MAX_VALUE), shards(children, 1, Long.MAX_VALUE));
        return new FixturePlan(spec(List.of(children, parents)), manifest);
    }

    private record FixturePlan(ImportTaskSpec spec, ImportManifest manifest) { }

    private void assertClosedAndClean() throws Exception {
        assertTrue(opened.isClosed(), "temporary staging tables must lose their owning session");
        assertEquals(2, scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"
                + (postgres() ? schema : database) + "' AND table_type='BASE TABLE'"));
    }

    private void executeSql(String sql) throws SQLException {
        try (Statement statement = keeper.createStatement()) { statement.execute(sql); }
    }
    private long scalar(String sql) throws SQLException {
        try (Statement statement = keeper.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    private ImportTaskSpec spec(List<ImportTableSource> sources) {
        return ImportTaskSpec.builder()
                .scope(postgres() ? ImportScope.SCHEMA : ImportScope.DATABASE)
                .format("CSV")
                .sourceKind("TRUSTED")
                .cycleStrategy("REJECT")
                .target(TaskTargetSnapshot.builder().databaseName(database).schemaName(schema).build())
                .tableSources(sources)
                .stagingPolicy(ImportStagingPolicy.builder()
                        .enabled(true).allVarchar(true).twoPhase(true).build())
                .validationOptions(ImportValidationOptions.builder()
                        .sourceProfiling(true).rowCount(true).checksum(true).orphanCheck(true).build())
                .build();
    }

    private ImportTableSource tableSource(String table, Path source, ImportOptions options) {
        return ImportTableSource.builder()
                .databaseName(database)
                .schemaName(schema)
                .tableName(table)
                .sourceFile(source.toString())
                .format("CSV")
                .options(options)
                .build();
    }

    private List<ImportManifestShard> shards(ImportTableSource source, int layer, long targetBytes)
            throws IOException {
        Path output = tempDirectory.resolve("shards-" + (++sourceSequence));
        return CsvShardPreprocessor.preprocess(Path.of(source.getSourceFile()).toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, output, source.getDatabaseName(), source.getSchemaName(),
                source.getTableName(), key(source.getTableName()), layer, targetBytes);
    }

    @SafeVarargs
    private final ImportManifest manifest(long taskId, ImportDependencyPlan plan,
            List<ImportTableDependency> dependencies, List<ImportManifestShard>... shardGroups) {
        List<ImportManifestShard> shards = new ArrayList<>();
        for (List<ImportManifestShard> group : shardGroups) {
            shards.addAll(group);
        }
        return ImportManifestBuilder.build(taskId, "STAGING_REQUIRED", "SHA-256:fixture-" + taskId,
                plan, dependencies, shards);
    }

    private ImportDependencyPlan dagPlan(List<List<String>> layers) {
        return ImportDependencyPlan.builder()
                .mode(ImportPlanMode.STAGING_FIRST)
                .layers(layers)
                .cyclicComponents(List.of())
                .selfReferencingTables(List.of())
                .shardKeys(Map.of())
                .stagingRequired(true)
                .cycleResolutionRequired(false)
                .build();
    }

    private ImportTableDependency dependency(String parentTable, String parentColumn,
            String childTable, String childColumn, String constraint) {
        return ImportTableDependency.builder()
                .parentSchemaName(schema)
                .parentTable(parentTable)
                .parentColumn(parentColumn)
                .parentTableKey(key(parentTable))
                .childSchemaName(schema)
                .childTable(childTable)
                .childColumn(childColumn)
                .childTableKey(key(childTable))
                .constraintName(constraint)
                .keySequence((short) 1)
                .deferrability((short) DatabaseMetaData.importedKeyNotDeferrable)
                .logical(false)
                .build();
    }

    private Path source(String name, String contents) throws IOException {
        return Files.writeString(tempDirectory.resolve(name + "-" + sourceSequence + ".csv"), contents,
                StandardCharsets.UTF_8);
    }

    private String key(String table) {
        return ImportTaskSourceSupport.tableKey(database, schema, table);
    }

    private static class RecordingContext implements TaskExecutionContext {

        private final Long taskId;
        private final Path artifactDirectory;
        private final AtomicInteger artifactSequence = new AtomicInteger();
        private final Map<String, List<Path>> artifacts = new LinkedHashMap<>();

        private RecordingContext(Long taskId, Path artifactDirectory) throws IOException {
            this.taskId = taskId;
            this.artifactDirectory = artifactDirectory;
            Files.createDirectories(artifactDirectory);
        }

        @Override
        public Long taskId() {
            return taskId;
        }

        @Override
        public void reportProgress(int progress, String stage, String message) {
            if ("IMPORTING".equals(stage)) publishing = true;
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

        private boolean cancelAtPublication;
        private boolean publishing;
        @Override
        public void checkCancelled() {
            if (cancelAtPublication && publishing) throw new ai.chat2db.community.domain.api.model.task.TaskCancelledException();
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            return createArtifact(TaskArtifactRole.OUTPUT, outputDirectory, fileName, mediaType);
        }

        @Override
        public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName,
                String mediaType) {
            String safeRole = role.replaceAll("[^A-Za-z0-9._-]", "_");
            Path path = artifactDirectory.resolve(String.format("%02d-%s-%s",
                    artifactSequence.incrementAndGet(), safeRole, fileName));
            artifacts.computeIfAbsent(role, ignored -> new ArrayList<>()).add(path);
            return ArtifactDraft.builder().role(role).temporaryFile(path.toFile())
                    .targetFile(path.toFile()).mediaType(mediaType).build();
        }

        @Override
        public void write(String content) {
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }

        private JSONObject jsonArtifact(String role) throws IOException {
            List<Path> paths = artifacts.get(role);
            assertFalse(paths == null || paths.isEmpty(), "Missing artifact role " + role);
            return JSON.parseObject(Files.readString(paths.get(paths.size() - 1), StandardCharsets.UTF_8));
        }

        private List<Path> artifactsStartingWith(String rolePrefix) {
            return artifacts.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(rolePrefix))
                    .flatMap(entry -> entry.getValue().stream())
                    .toList();
        }
    }
}
