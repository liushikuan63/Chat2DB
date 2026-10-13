package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.*;
import ai.chat2db.community.domain.api.service.task.*;
import ai.chat2db.plugin.h2.H2Plugin;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.management.ManagementFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in local H2 staging benchmark; deliberately excluded from the default *Test gate.
 * Measures production staging execution only: source generation/preprocessing and assertions
 * are outside the timer. This is neither a MySQL fast-path benchmark nor an SLA gate.
 */
class ImportPerformanceBaselineIT {
    private static final int ROWS = 50_000;
    private static final long SEED = 20_261_013L;
    @TempDir Path temporary;

    @Test
    void measuresVerifiedStagingSamples() throws Exception {
        Path output = Path.of("target", "performance-baseline");
        Files.createDirectories(output);
        // Never leave a stale successful result when this invocation fails.
        Files.deleteIfExists(output.resolve("h2-staging.json"));
        Path source = temporary.resolve("seed-20261013-50000.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(source, StandardCharsets.UTF_8)) {
            writer.write("ID,PAYLOAD\n");
            for (int row = 1; row <= ROWS; row++) writer.write(row + "," + (SEED + row) + "\n");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("fixture", "H2 named in-memory, empty target per run; no user data");
        result.put("seed", SEED);
        result.put("sourceRows", ROWS);
        result.put("sourceBytes", Files.size(source));
        result.put("sourceSha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("timingBoundary", "StagingManifestImporter.execute including report write and importer connection cleanup; excludes CSV creation/sharding/DB verification");
        result.put("heapProxy", "JVM-wide used heap sampled every 5ms plus endpoints; not RSS, allocation rate or exact peak; no forced GC");
        result.put("modeBoundary", "STANDARD staging pipeline, single connection, batch 500. Ordinary CSV strategy, queue latency, UI upload and MySQL fast path are not measured.");
        List<Map<String,Object>> samples = new ArrayList<>();
        result.put("runs", samples);
        IPlugin previous = Chat2DBContext.PLUGIN_MAP.put("H2", new H2Plugin());
        String url = "jdbc:h2:mem:import_baseline_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection keeper = DriverManager.getConnection(url, "sa", "")) {
            result.put("databaseVersion", keeper.getMetaData().getDatabaseProductVersion());
            String database = keeper.getCatalog();
            ConnectInfo info = new ConnectInfo();
            info.setDbType("H2"); info.setDatabaseName(database); info.setSchemaName("PUBLIC"); info.setConnection(keeper);
            Chat2DBContext.putContext(info);
            try (Statement statement = keeper.createStatement()) {
                statement.execute("CREATE TABLE BENCH (ID BIGINT PRIMARY KEY, PAYLOAD VARCHAR(64) NOT NULL)");
            }
            String key = database + ".PUBLIC.BENCH";
            var shards = CsvShardPreprocessor.preprocess(source.toFile(), StandardCharsets.UTF_8,
                    CSVFormat.DEFAULT, temporary.resolve("shards"), database, "PUBLIC", "BENCH", key, 0, Long.MAX_VALUE);
            assertEquals(1, shards.size()); assertEquals(ROWS, shards.get(0).getEstimatedRows());
            var plan = ImportDependencyPlan.builder().mode(ImportPlanMode.STAGING_FIRST)
                    .layers(List.of(List.of(key))).cyclicComponents(List.of()).selfReferencingTables(List.of())
                    .shardKeys(Map.of()).stagingRequired(true).cycleResolutionRequired(false).build();
            var manifest = ImportManifestBuilder.build(1200L, "STAGING_REQUIRED", "SHA-256:benchmark-fixture", plan, List.of(), shards);
            var table = ImportTableSource.builder().databaseName(database).schemaName("PUBLIC").tableName("BENCH")
                    .sourceFile(source.toString()).format("CSV").options(ImportOptions.builder().onError("ABORT").build()).build();
            // Real admission assessment, no opaque staged identity is fabricated and no thresholds are relaxed.
            var candidate = ImportTaskSpec.builder().format("CSV").sourceFile(source.toString()).mode("ULTRA_FAST")
                    .confirmedNoStrongRelations(true).options(ImportOptions.builder().onError("ABORT").build())
                    .target(TaskTargetSnapshot.builder().databaseName(database).schemaName("PUBLIC").tableName("BENCH").build()).build();
            var columns = Chat2DBContext.getDbMetaData().columns(keeper,
                    new ai.chat2db.spi.model.request.TableMetadataRequest(database, "PUBLIC", "BENCH"));
            assertEquals(2, columns.size());
            var admission = ImportParallelAdmission.assess(candidate, columns);
            assertEquals("STANDARD", admission.getEffectiveMode());
            assertFalse(admission.isParallelAllowed());
            assertTrue(admission.getFindings().stream().anyMatch(f -> "G0".equals(f.getCode())));
            result.put("ultraFastAdmission", admission);
            result.put("ultraFastExecuted", false);
            result.put("ultraFastGap", "50k rows below default admission minimum; direct fixture lacks staged opaque identity. No parallel throughput sample claimed.");
            for (String scenario : List.of("STANDARD_STAGING_COMMIT", "REHEARSAL_FULL", "REHEARSAL_5_PERCENT")) {
                for (int round = 0; round < 4; round++) {
                    boolean rehearsal = !scenario.equals("STANDARD_STAGING_COMMIT");
                    int percent = scenario.equals("REHEARSAL_5_PERCENT") ? 5 : 100;
                    long expectedRows = percent == 5 ? 2500L : 50000L;
                    try (Statement statement = keeper.createStatement()) { statement.executeUpdate("DELETE FROM BENCH"); }
                    assertEquals(0, scalar(keeper, "SELECT COUNT(*) FROM BENCH"));
                    long sessionsBefore = scalar(keeper, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS");
                    var spec = ImportTaskSpec.builder().scope(ImportScope.SCHEMA).format("CSV").mode("STANDARD")
                            .sourceKind("THIRD_PARTY").cycleStrategy("STAGING_TWO_PHASE")
                            .target(candidate.getTarget()).tableSources(List.of(table))
                            .stagingPolicy(ImportStagingPolicy.builder().enabled(true).allVarchar(true).twoPhase(true).build())
                            .validationOptions(ImportValidationOptions.builder().sourceProfiling(true).rowCount(true).checksum(true).build())
                            .rollbackOptions(ImportRollbackOptions.builder().fullRollback(rehearsal).rehearsal(rehearsal).build())
                            .performanceSamplePercent(rehearsal ? percent : null).build();
                    ReportContext context = new ReportContext(1200L, temporary.resolve(scenario + round));
                    List<Connection> opened = new ArrayList<>();
                    AtomicInteger peakConnections = new AtomicInteger();
                    long heapBefore = heap();
                    AtomicLong peakHeap = new AtomicLong(heapBefore);
                    ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
                    sampler.scheduleAtFixedRate(() -> peakHeap.accumulateAndGet(heap(), Math::max), 0, 5, TimeUnit.MILLISECONDS);
                    long started = System.nanoTime();
                    long elapsed;
                    try {
                        new StagingManifestImporter(ignored -> {
                            Connection connection = DriverManager.getConnection(url, "sa", "");
                            opened.add(connection);
                            peakConnections.accumulateAndGet((int) scalar(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS"), Math::max);
                            return connection;
                        }).execute(spec, context, manifest);
                        elapsed = System.nanoTime() - started;
                    } finally {
                        peakHeap.accumulateAndGet(heap(), Math::max);
                        sampler.shutdownNow();
                        assertTrue(sampler.awaitTermination(5, TimeUnit.SECONDS));
                        for (Connection connection : opened) {
                            boolean closed = connection.isClosed();
                            if (!closed) connection.close();
                            assertTrue(closed, "production importer must close its connection");
                        }
                    }
                    JSONObject report = context.report();
                    assertEquals(expectedRows, report.getJSONArray("sourceProfiles").getJSONObject(0).getLongValue("sourceRows"));
                    assertEquals("MATCHED", report.getJSONArray("checksums").getJSONObject(0).getString("status"));
                    assertTrue(report.getJSONArray("rowCounts").getJSONObject(0).getBooleanValue("matched"));
                    if (rehearsal) {
                        assertEquals("REHEARSAL_ROLLED_BACK", report.getJSONObject("transaction").getString("outcome"));
                        assertTrue(report.getJSONObject("transaction").getBooleanValue("rollbackVerified"));
                    }
                    long count = scalar(keeper, "SELECT COUNT(*) FROM BENCH");
                    long sum = scalar(keeper, "SELECT COALESCE(SUM(ID),0) FROM BENCH");
                    long payloadSum = scalar(keeper, "SELECT COALESCE(SUM(CAST(PAYLOAD AS BIGINT)),0) FROM BENCH");
                    assertEquals(rehearsal ? 0 : 50000, count);
                    assertEquals(rehearsal ? 0 : 1250025000L, sum);
                    assertEquals(rehearsal ? 0 : 1014300675000L, payloadSum);
                    long sessionsAfter = scalar(keeper, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS");
                    assertEquals(sessionsBefore, sessionsAfter);
                    assertEquals(1, scalar(keeper, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'"));
                    Map<String,Object> sample = new LinkedHashMap<>();
                    sample.put("scenario", scenario); sample.put("round", round); sample.put("warmup", round == 0);
                    sample.put("verified", true); sample.put("rowsProcessed", expectedRows); sample.put("targetRowsAfter", count);
                    sample.put("targetIdSumAfter", sum); sample.put("targetPayloadSumAfter", payloadSum);
                    sample.put("elapsedNanos", elapsed); sample.put("rowsPerSecond", expectedRows * 1_000_000_000.0 / elapsed);
                    sample.put("heapUsedBefore", heapBefore); sample.put("heapUsedPeakProxy", peakHeap.get());
                    sample.put("heapUsedPeakIncreaseProxy", Math.max(0, peakHeap.get() - heapBefore));
                    sample.put("connectionsOpened", opened.size()); sample.put("sessionsBefore", sessionsBefore);
                    sample.put("sessionsObservedPeak", peakConnections.get()); sample.put("sessionsAfter", sessionsAfter);
                    sample.put("cleanup", "all importer connections closed; sessions restored; temporary staging tables absent");
                    sample.put("productionReport", report); samples.add(sample);
                    System.out.printf("[import-baseline] %s round=%d warmup=%s rows=%d nanos=%d rows/s=%.2f%n", scenario, round, round==0, expectedRows, elapsed, expectedRows*1e9/elapsed);
                }
            }
            try (Statement statement = keeper.createStatement()) { statement.execute("DROP ALL OBJECTS"); }
            assertEquals(0, scalar(keeper, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'"));
        } finally {
            Chat2DBContext.removeContext();
            if (previous == null) Chat2DBContext.PLUGIN_MAP.remove("H2"); else Chat2DBContext.PLUGIN_MAP.put("H2", previous);
        }
        assertEquals(12, samples.size());
        result.put("cleanup", "DROP ALL OBJECTS verified; keeper closed; named DB has no DB_CLOSE_DELAY and is destroyed at last close; plugin/context restored; @TempDir owns files");
        result.put("completedAt", java.time.Instant.now().toString());
        Files.writeString(output.resolve("h2-staging.json"), JSON.toJSONString(result, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
    }
    private static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private static final class ReportContext implements TaskExecutionContext {

        private final Long taskId;
        private final Path artifactDirectory;
        private final AtomicInteger artifactSequence = new AtomicInteger();
        private final Map<String, List<Path>> artifacts = new LinkedHashMap<>();

        private ReportContext(Long taskId, Path artifactDirectory) throws IOException {
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

        private JSONObject report() throws IOException {
            List<Path> paths = artifacts.get(TaskArtifactRole.IMPORT_REPORT);
            assertFalse(paths == null || paths.isEmpty(), "missing import report");
            return JSON.parseObject(Files.readString(paths.get(paths.size() - 1), StandardCharsets.UTF_8));
        }
    }
}
