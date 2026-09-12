package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import ai.chat2db.plugin.mysql.MysqlPlugin;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-MySQL end-to-end check of the strong-relationship multi-table import: two tables joined by a
 * physical foreign key are staged and published in dependency order, the post-import orphan check
 * runs against the real targets, and a row that violates the relationship aborts before anything is
 * published.
 *
 * <p>Skips unless MySQL credentials are supplied through the environment, matching the contract of
 * {@link ai.chat2db.community.domain.core.impl.task.MySQLTaskRoundTripIT}.
 */
class MultiTableStrongRelationMysqlIT {

    private static final String DB_TYPE = "MYSQL_MULTI_IT";

    private static final String MYSQL_DRIVER_NAME = "mysql-multi-it-connector.jar";

    private static final List<String> TRACKED_TABLES =
            List.of("PARENT_ORDERS", "CHILD_ITEMS", "ORDERS_A", "ITEMS_A");

    private static String previousUserHome;

    @TempDir
    Path tempDirectory;

    private Connection connection;
    private IPlugin previousPlugin;
    private String url;
    private String database;
    private int sourceSequence;

    @BeforeAll
    static void seedMysqlDriver() throws Exception {
        previousUserHome = System.getProperty("user.home");
        File tempHome = Files.createTempDirectory("chat2db-mysql-multi-it-home").toFile();
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

    @BeforeEach
    void setUp() throws Exception {
        String host = envOr("C2D_MYSQL_HOST", "127.0.0.1");
        String port = envOr("C2D_MYSQL_PORT", "3306");
        String user = envOr("C2D_MYSQL_USER", "root");
        String password = System.getenv("C2D_MYSQL_PASSWORD");
        database = envOr("C2D_MYSQL_DB", "c2d_multi_it");
        assumeTrue(password != null && !password.isBlank(),
                "C2D_MYSQL_PASSWORD is not set; local MySQL integration test skipped");
        String bootstrapUrl = "jdbc:mysql://" + host + ":" + port + "/?allowPublicKeyRetrieval=true"
                + "&useSSL=false&serverTimezone=UTC&connectTimeout=5000";
        connection = DriverManager.getConnection(bootstrapUrl, user, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database);
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        }
        connection.close();
        url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC&connectTimeout=5000";
        connection = DriverManager.getConnection(url, user, password);
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new MysqlPlugin());
        ConnectInfo parent = new ConnectInfo();
        parent.setDbType(DB_TYPE);
        parent.setDatabaseName(database);
        Chat2DBContext.putContext(parent);
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
        if (connection != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database);
            } catch (Exception cleanupFailure) {
                // named local fixture; never mask the test result
            }
            connection.close();
        }
    }

    @Test
    void publishesParentBeforeChildAndValidatesOrphansAgainstRealTargets() throws Exception {
        execute("CREATE TABLE PARENT_ORDERS (ID INT PRIMARY KEY, CODE VARCHAR(32), AMOUNT INT)");
        execute("CREATE TABLE CHILD_ITEMS (ID INT PRIMARY KEY, ORDER_ID INT NOT NULL, SKU VARCHAR(32),"
                + " CONSTRAINT FK_ITEMS_ORDER FOREIGN KEY (ORDER_ID) REFERENCES PARENT_ORDERS(ID))");

        Path parentCsv = source("parent", "ID,CODE,AMOUNT\n1,A-1,10\n2,A-2,20\n");
        Path childCsv = source("child", "ID,ORDER_ID,SKU\n100,1,SKU-1\n101,2,SKU-2\n");
        ImportTableSource parent = tableSource("PARENT_ORDERS", parentCsv);
        ImportTableSource child = tableSource("CHILD_ITEMS", childCsv);
        List<ImportManifestShard> parentShards = shards(parent, 0);
        List<ImportManifestShard> childShards = shards(child, 1);
        List<String> parentShardIds = parentShards.stream().map(ImportManifestShard::getShardId).toList();
        childShards.forEach(shard -> shard.setDependencyShardIds(parentShardIds));
        ImportDependencyPlan plan = plan(List.of(List.of(key("PARENT_ORDERS")), List.of(key("CHILD_ITEMS"))));
        ImportManifest manifest = ImportManifestBuilder.build(901L, "STAGING_REQUIRED",
                "SHA-256:mysql-multi-901", plan,
                List.of(dependency("PARENT_ORDERS", "ID", "CHILD_ITEMS", "ORDER_ID", "FK_ITEMS_ORDER")),
                concat(parentShards, childShards));
        ImportTaskSpec spec = spec(List.of(parent, child));
        RecordingContext context = new RecordingContext(901L, tempDirectory.resolve("artifacts"));
        List<String> published = new ArrayList<>();
        StagingManifestImporter importer = new StagingManifestImporter(ignored -> openStagedConnection(published));

        importer.execute(spec, context, manifest);

        assertEquals(2L, count("PARENT_ORDERS"));
        assertEquals(2L, count("CHILD_ITEMS"));
        assertEquals(2L, scalar("SELECT COUNT(*) FROM CHILD_ITEMS c JOIN PARENT_ORDERS p ON p.ID=c.ORDER_ID"),
                "every child row must resolve to its parent");
        JSONObject report = context.reportArtifact();
        assertEquals("COMMITTED", report.getJSONObject("transaction").getString("outcome"));
        assertEquals(2, report.getJSONArray("rowCounts").size());
        assertTrue(published.indexOf("PARENT_ORDERS") < published.indexOf("CHILD_ITEMS"),
                "the parent table must be published before the child table, saw " + published);
        assertTrue(report.getJSONArray("postImportOrphanChecks").size() >= 1,
                "the relationship must be orphan-checked against the real targets");
    }

    @Test
    void rejectsAnOrphanRowBeforePublishingAnything() throws Exception {
        execute("CREATE TABLE ORDERS_A (ID INT PRIMARY KEY, CODE VARCHAR(32))");
        execute("CREATE TABLE ITEMS_A (ID INT PRIMARY KEY, ORDER_ID INT NOT NULL,"
                + " CONSTRAINT FK_ITEMS_A FOREIGN KEY (ORDER_ID) REFERENCES ORDERS_A(ID))");

        Path ordersCsv = source("orders-a", "ID,CODE\n1,O-1\n");
        Path itemsCsv = source("items-a", "ID,ORDER_ID\n200,1\n201,999\n");
        ImportTableSource orders = tableSource("ORDERS_A", ordersCsv);
        ImportTableSource items = tableSource("ITEMS_A", itemsCsv);
        List<ImportManifestShard> orderShards = shards(orders, 0);
        List<ImportManifestShard> itemShards = shards(items, 1);
        List<String> orderShardIds = orderShards.stream().map(ImportManifestShard::getShardId).toList();
        itemShards.forEach(shard -> shard.setDependencyShardIds(orderShardIds));
        ImportDependencyPlan plan = plan(List.of(List.of(key("ORDERS_A")), List.of(key("ITEMS_A"))));
        ImportManifest manifest = ImportManifestBuilder.build(902L, "STAGING_REQUIRED",
                "SHA-256:mysql-multi-902", plan,
                List.of(dependency("ORDERS_A", "ID", "ITEMS_A", "ORDER_ID", "FK_ITEMS_A")),
                concat(orderShards, itemShards));
        ImportTaskSpec spec = spec(List.of(orders, items));
        RecordingContext context = new RecordingContext(902L, tempDirectory.resolve("artifacts-bad"));
        StagingManifestImporter importer = new StagingManifestImporter(ignored -> openStagedConnection(new ArrayList<>()));

        boolean aborted = false;
        try {
            importer.execute(spec, context, manifest);
        } catch (RuntimeException expected) {
            aborted = true;
        }

        assertTrue(aborted, "the staged orphan must abort the publish");
        assertEquals(0L, count("ORDERS_A"), "an orphan row must not publish the parent table");
        assertEquals(0L, count("ITEMS_A"), "an orphan row must not publish the child table");
    }

    /**
     * Records which tracked target tables were published through this connection. The importer talks
     * to the target through both {@code createStatement} and {@code prepareStatement}, so the
     * connection and every statement it hands out are proxied.
     */
    private Connection openStagedConnection(List<String> published) throws Exception {
        Connection staged = DriverManager.getConnection(url, envOr("C2D_MYSQL_USER", "root"),
                System.getenv("C2D_MYSQL_PASSWORD"));
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    Object result = invokeDelegate(staged, method, arguments);
                    if (arguments != null && arguments.length > 0 && arguments[0] instanceof String sql
                            && ("prepareStatement".equals(method.getName()) || "execute".equals(method.getName())
                                || "executeUpdate".equals(method.getName()))) {
                        recordPublished(sql, published);
                    }
                    if (!"createStatement".equals(method.getName()) || !(result instanceof Statement statement)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{Statement.class}, (statementProxy, statementMethod, statementArguments) -> {
                                if (statementArguments != null && statementArguments.length > 0
                                        && statementArguments[0] instanceof String sql) {
                                    recordPublished(sql, published);
                                }
                                return invokeDelegate(statement, statementMethod, statementArguments);
                            });
                });
    }

    private static void recordPublished(String sql, List<String> published) {
        // The importer quotes the qualified target (for MySQL: `db`.`TABLE`), so compare against the
        // unquoted identifier instead of a bare "INSERT INTO TABLE" prefix.
        String normalized = sql.toUpperCase().replace("`", "").replace("\"", "");
        int insert = normalized.indexOf("INSERT INTO ");
        if (insert < 0) {
            return;
        }
        String target = normalized.substring(insert + "INSERT INTO ".length()).trim();
        for (String table : TRACKED_TABLES) {
            boolean matches = target.startsWith(table) || target.contains("." + table);
            if (matches && !published.contains(table)) {
                published.add(table);
            }
        }
    }

    private static Object invokeDelegate(Object delegate, java.lang.reflect.Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private void execute(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String table) throws Exception {
        return scalar("SELECT COUNT(*) FROM " + table);
    }

    private long scalar(String sql) throws Exception {
        try (Connection reader = DriverManager.getConnection(url, envOr("C2D_MYSQL_USER", "root"),
                System.getenv("C2D_MYSQL_PASSWORD"));
             Statement statement = reader.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private ImportTaskSpec spec(List<ImportTableSource> sources) {
        return ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .scope(ImportScope.DATABASE)
                .tableSources(sources)
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).databaseName(database).build())
                .options(ImportOptions.builder().charset("UTF-8").delimiter(",").onError("ABORT").build())
                .build();
    }

    private ImportTableSource tableSource(String table, Path file) {
        return ImportTableSource.builder()
                .databaseName(database)
                .tableName(table)
                .sourceFile(file.toString())
                .displayFileName(file.getFileName().toString())
                .format("CSV")
                .build();
    }

    private List<ImportManifestShard> shards(ImportTableSource source, int layer) throws IOException {
        Path output = tempDirectory.resolve("shards-" + (++sourceSequence));
        return CsvShardPreprocessor.preprocess(Path.of(source.getSourceFile()).toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, output, source.getDatabaseName(),
                source.getSchemaName(), source.getTableName(), key(source.getTableName()), layer, 4096L);
    }

    private static ImportDependencyPlan plan(List<List<String>> layers) {
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

    private ImportTableDependency dependency(String parentTable, String parentColumn, String childTable,
            String childColumn, String constraint) {
        return ImportTableDependency.builder()
                .parentDatabaseName(database)
                .parentTable(parentTable)
                .parentColumn(parentColumn)
                .parentTableKey(key(parentTable))
                .childDatabaseName(database)
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
        return database + "." + table;
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> merged = new ArrayList<>(first);
        merged.addAll(second);
        return merged;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class RecordingContext implements TaskExecutionContext {

        private final Long taskId;
        private final Path artifactDirectory;

        private RecordingContext(Long taskId, Path artifactDirectory) {
            this.taskId = taskId;
            this.artifactDirectory = artifactDirectory;
        }

        @Override
        public Long taskId() {
            return taskId;
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
            return createArtifact(TaskArtifactRole.OUTPUT, outputDirectory, fileName, mediaType);
        }

        @Override
        public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName,
                String mediaType) {
            try {
                Files.createDirectories(artifactDirectory);
                Path target = artifactDirectory.resolve(fileName);
                return ArtifactDraft.builder().role(role).temporaryFile(target.toFile())
                        .targetFile(target.toFile()).mediaType(mediaType).build();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private JSONObject reportArtifact() throws IOException {
            try (var files = Files.list(artifactDirectory)) {
                Path report = files.filter(path -> path.getFileName().toString().contains("report.json"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("missing import report"));
                return JSON.parseObject(Files.readString(report, StandardCharsets.UTF_8));
            }
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
    }
}
