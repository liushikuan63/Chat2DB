package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskCompression;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.extension.ExportCell;
import ai.chat2db.community.domain.api.model.task.extension.ExportCellContext;
import ai.chat2db.community.domain.api.model.task.pipeline.ExportSchema;
import ai.chat2db.community.domain.api.model.task.pipeline.FormatSink;
import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.metadata.PrimaryKey;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionOperation;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.model.sql.extension.SqlResultColumnContext;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.AdaptiveBatchSizer;
import ai.chat2db.community.domain.core.impl.task.AdaptiveConcurrencyGate;
import ai.chat2db.community.domain.core.impl.task.TaskResumeJournal;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.KeyBound;
import ai.chat2db.spi.model.request.KeysetPageLimitRequest;
import ai.chat2db.spi.model.request.SelectKeyRangeSqlRequest;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.ResultSetUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


@Slf4j
public abstract class BaseExporter implements IExportStrategy {

    private final ExportCellProcessorChain exportCellProcessorChain;

    private final SqlExecutionPolicyManager sqlExecutionPolicyManager;

    protected String contentType;

    protected String suffix;

    /**
     * JDBC fetch size for exports; independent from the sink flush size.
     */
    public static final int EXPORT_BATCH_ROWS = 1000;

    /**
     * Rows handed to a {@link FormatSink} per batch.
     */
    public static final int SINK_BATCH_ROWS = 500;

    /**
     * Resume-state kind written by the checkpointed export path.
     */
    private static final String RESUME_KIND_KEYSET = "KEYSET";

    /**
     * Keyset page size used by one shard worker.
     */
    private static final int SHARD_PAGE_ROWS = 50_000;

    /**
     * Queue token marking that a shard finished (cleanly or after failing).
     */
    private static final Object SHARD_END = new Object();

    /**
     * Shards per table export. Defaults to an automatic cap of {@code min(16, cores)}; set the
     * {@code chat2db.task.shard.max-parallelism} property explicitly to pin or disable (1) the
     * fan-out. Unmanaged instantiation keeps 1, the single-cursor path. The active fan-out always
     * starts at 2 and is tuned inside [2, workers] by the adaptive gate.
     */
    @org.springframework.beans.factory.annotation.Value("${chat2db.task.shard.max-parallelism:0}")
    private int shardMaxParallelism = 1;

    protected BaseExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        this.exportCellProcessorChain = exportCellProcessorChain;
        this.sqlExecutionPolicyManager = sqlExecutionPolicyManager;
    }

    @Override
    public void run(ExportTaskSpec spec, TaskExecutionContext context, File outputFile) {
        context.checkCancelled();
        List<String> tableNames = spec.getTableNames();
        if (CollectionUtils.isEmpty(tableNames)) {
            throw new IllegalArgumentException("tableNames should not be null or empty");
        }
        boolean resuming = isResuming(spec, context, tableNames, outputFile);
        try {
            if (tableNames.size() == 1) {
                context.reportProgress(20, TaskStage.EXPORTING.name(), "Exporting table data");
                try (OutputStream file = openArtifactStream(outputFile, resuming);
                        OutputStream output = wrapForCompression(file, spec)) {
                    singleWithEvents(spec, context, tableNames.get(0), output, 0, 1, resuming);
                    output.flush();
                }
            } else {
                multi(spec, context, outputFile);
            }
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("export data error", e);
            throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(), "Could not export table data", e);
        }
    }

    /**
     * Resume only ever applies to a single-table checkpointed export whose draft file still agrees
     * with the persisted checkpoint; ZIP containers and plain exports always rewrite their artifact
     * from the start.
     */
    private boolean isResuming(ExportTaskSpec spec, TaskExecutionContext context, List<String> tableNames,
            File outputFile) {
        Integer checkpoint = spec.getCheckpointRows();
        if (checkpoint == null || checkpoint <= 0 || tableNames.size() != 1
                || context.taskId() == null || context.resumeStates().isEmpty()) {
            return false;
        }
        return alignArtifactForResume(outputFile, lastResumeState(context));
    }

    /**
     * Makes the draft file agree with the last persisted checkpoint before a resumed run appends to
     * it. Bytes flushed past the checkpoint are truncated away, and a file that lost buffered bytes
     * or went missing entirely cannot be continued: the caller then rewrites the artifact from the
     * start so the output never duplicates or silently drops rows.
     */
    private boolean alignArtifactForResume(File outputFile, ResumeState last) {
        if (last == null || last.getBytesDone() == null) {
            // A checkpoint without a recorded byte count cannot be verified; keep the old append rule.
            return outputFile.isFile() && outputFile.length() > 0;
        }
        long durableBytes = last.getBytesDone();
        if (!outputFile.isFile()) {
            log.info("Resume draft {} is missing; the export restarts from the beginning", outputFile);
            return false;
        }
        long currentLength = outputFile.length();
        if (currentLength < durableBytes) {
            log.info("Resume draft {} holds {} bytes but its checkpoint recorded {}; the export restarts",
                    outputFile, currentLength, durableBytes);
            return false;
        }
        if (currentLength > durableBytes) {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    outputFile.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
                channel.truncate(durableBytes);
            } catch (IOException e) {
                throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                        "Could not align the export draft with its checkpoint", e);
            }
        }
        return true;
    }

    private ResumeState lastResumeState(TaskExecutionContext context) {
        return context.resumeStates().stream()
                .filter(state -> state.getShardNo() != null && state.getShardNo() == 0)
                .filter(state -> RESUME_KIND_KEYSET.equals(state.getKind()))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static OutputStream openArtifactStream(File outputFile, boolean resuming) throws IOException {
        if (resuming && outputFile.isFile() && outputFile.length() > 0) {
            return Files.newOutputStream(outputFile.toPath(), java.nio.file.StandardOpenOption.APPEND);
        }
        return Files.newOutputStream(outputFile.toPath());
    }

    private void singleWithEvents(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
            OutputStream output, int tableIndex, int totalTables, boolean resuming) throws Exception {
        logTableEvent(context, TaskEventCode.TABLE_EXPORT_STARTED.name(),
                tableProgressMessage("Exporting table", tableName, tableIndex, totalTables), tableName,
                tableIndex, totalTables);
        singleExport(spec, context, tableName, output, resuming);
        logTableEvent(context, TaskEventCode.TABLE_EXPORT_COMPLETED.name(),
                tableProgressMessage("Table export completed", tableName, tableIndex, totalTables), tableName,
                tableIndex, totalTables);
    }

    /**
     * Multi-table exports stream each table straight into one ZIP entry instead of writing N
     * intermediate files and copying them, halving the peak disk usage.
     */
    private void multi(ExportTaskSpec spec, TaskExecutionContext context, File outputFile) throws Exception {
        List<String> tableNames = spec.getTableNames();
        int n = tableNames.size();
        try (OutputStream file = Files.newOutputStream(outputFile.toPath());
                ZipOutputStream zip = new ZipOutputStream(wrapForCompression(file, spec))) {
            for (int i = 0; i < n; i++) {
                context.checkCancelled();
                String tableName = tableNames.get(i);
                if (StringUtils.isEmpty(tableName)) {
                    throw new IllegalArgumentException("tableName should not be null or empty");
                }
                String safeTableName = new File(tableName).getName();
                logTableEvent(context, TaskEventCode.TABLE_EXPORT_STARTED.name(),
                        tableProgressMessage("Exporting table", tableName, i, n), tableName, i, n);
                zip.putNextEntry(new ZipEntry(safeTableName + suffix));
                singleExport(spec, context, tableName, new EntryStream(zip), false);
                zip.closeEntry();
                logTableEvent(context, TaskEventCode.TABLE_EXPORT_COMPLETED.name(),
                        tableProgressMessage("Table export completed", tableName, i, n), tableName, i, n);
                context.reportProgress(Math.min(90, 10 + ((i + 1) * 80 / n)), TaskStage.EXPORTING.name(),
                        "Exported " + (i + 1) + " of " + n + " tables");
            }
            context.checkCancelled();
            context.reportProgress(92, TaskStage.FINALIZING.name(), "Finalizing ZIP export archive");
            context.logInfo(TaskEventCode.FILE_FINALIZING.name(), "Finalizing ZIP export archive",
                    Map.of(TaskConstants.FILE_FORMAT_DETAIL_KEY, "ZIP",
                            TaskConstants.TOTAL_TABLES_DETAIL_KEY, n));
        }
    }

    private static OutputStream wrapForCompression(OutputStream file, ExportTaskSpec spec) throws IOException {
        BufferedOutputStream buffered = new BufferedOutputStream(file);
        return TaskCompression.GZIP.equalsIgnoreCase(StringUtils.trimToEmpty(spec.getCompression()))
                ? new GZIPOutputStream(buffered) : buffered;
    }

    /**
     * Shared producer loop. Without checkpoints it streams the planned query in one statement;
     * with {@code checkpointRows} set and a single-column primary key it walks keyset pages,
     * persisting a resume cursor after each page so an interrupted export continues where it
     * stopped.
     */
    protected final void streamTable(ExportTaskSpec spec, String tableName, TaskExecutionContext context,
            OutputStream output, SinkFactory sinkFactory, ExportValueMode mode, int fetchRows,
            ExportProgressLogger progressLogger, boolean resuming) {
        SqlExecutionPlan executionPlan = getQueryPlan(spec, tableName);
        progressLogger.queryStarted("Reading table data from " + tableName);
        boolean ultra = TaskExecutionMode.isUltraFast(spec.getMode());
        String checkpointKey = checkpointKeyColumn(spec, context, tableName);
        if (checkpointKey != null) {
            streamKeysetPages(spec, tableName, context, output, sinkFactory, mode, executionPlan,
                    progressLogger, checkpointKey, resuming, ultra);
        } else if (ultra && tryShardExport(spec, tableName, context, output, sinkFactory, mode,
                executionPlan, progressLogger)) {
            return;
        } else {
            if (ultra) {
                log.warn("Ultra-fast export requested but {} cannot be sharded (small table, non-numeric "
                        + "single key, or a dialect without keyset sharding); falling back to the standard "
                        + "single-cursor path", tableName);
            }
            DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), executionPlan.getSql(),
                    fetchRows, resultSet -> {
                        try {
                            streamResultSet(spec, tableName, context, output, sinkFactory, mode,
                                    executionPlan, resultSet, progressLogger, ultra);
                        } catch (IOException e) {
                            throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                                    "Could not write export file", e);
                        }
                    },
                    context, context::checkCancelled);
        }
        progressLogger.queryCompleted("Table data read completed");
        progressLogger.fileFinalizing();
    }

    /**
     * The single-column primary key usable for checkpointed paging, or {@code null} to stream.
     * A checkpointed run without a usable key must not silently downgrade, because its stored
     * cursor would no longer match the key columns; a plain run may.
     */
    private String checkpointKeyColumn(ExportTaskSpec spec, TaskExecutionContext context, String tableName) {
        Integer checkpoint = spec.getCheckpointRows();
        if (checkpoint == null || checkpoint <= 0 || context.taskId() == null) {
            return null;
        }
        if (!Chat2DBContext.getDbManager().getExportCapability().isKeysetSharding()) {
            throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "Checkpointed export is not supported by this database dialect");
        }
        try {
            List<PrimaryKey> primaryKeys = DefaultSQLExecutor.getInstance().getPrimaryKeys(
                    Chat2DBContext.getConnection(), spec.getTarget().getDatabaseName(),
                    spec.getTarget().getSchemaName(), tableName);
            if (primaryKeys == null || primaryKeys.size() != 1) {
                throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                        "Checkpointed export requires exactly one primary key column on " + tableName);
            }
            String keyColumn = primaryKeys.get(0).getColumnName();
            if (StringUtils.isBlank(keyColumn)) {
                throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                        "Checkpointed export requires a named primary key on " + tableName);
            }
            return keyColumn;
        } catch (TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "Could not read the primary key of " + tableName, e);
        }
    }

    private void streamKeysetPages(ExportTaskSpec spec, String tableName, TaskExecutionContext context,
            OutputStream output, SinkFactory sinkFactory, ExportValueMode mode, SqlExecutionPlan executionPlan,
            ExportProgressLogger progressLogger, String keyColumn, boolean resuming, boolean adaptiveSizing) {
        int pageSize = Math.max(1, spec.getCheckpointRows());
        // A non-resumable run must not pick up a stale cursor: the artifact is being rewritten.
        KeysetRun run = new KeysetRun(keyColumn, resuming ? resumeCursor(context, keyColumn) : null, resuming,
                adaptiveSizing);
        try {
            while (true) {
                String pageSql = Chat2DBContext.getSqlBuilder().dql().buildKeysetPageLimit(
                        KeysetPageLimitRequest.builder()
                                .databaseName(spec.getTarget().getDatabaseName())
                                .schemaName(spec.getTarget().getSchemaName())
                                .tableName(tableName)
                                .keyColumns(List.of(keyColumn))
                                .bounds(run.bounds())
                                .fetchSize(pageSize)
                                .build());
                DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), pageSql, pageSize,
                        resultSet -> {
                            try {
                                readKeysetRows(spec, tableName, context, output, sinkFactory, mode,
                                        executionPlan, resultSet, progressLogger, run);
                            } catch (IOException e) {
                                throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                                        "Could not write export file", e);
                            }
                        },
                        context, context::checkCancelled);
                if (run.pageRows == 0) {
                    break;
                }
                context.checkpoint(ResumeState.builder()
                        .shardNo(0)
                        .kind(RESUME_KIND_KEYSET)
                        .cursorJson(run.cursorJson())
                        .rowsDone(run.rowsDone)
                        .bytesDone(durableByteCount(run))
                        .updatedAt(new Date())
                        .build());
                if (run.pageRows < pageSize) {
                    break;
                }
            }
            if (run.sink != null) {
                try {
                    run.sink.finishTable(tableName);
                } catch (IOException e) {
                    throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                            "Could not write export file", e);
                }
            }
        } finally {
            if (run.sink != null) {
                closeSink(run.sink);
            }
        }
    }

    /**
     * Rows below this span stay on the single-cursor path; sharding such tables only adds load.
     */
    private static final long MIN_ROWS_PER_SHARD = 5_000L;

    /**
     * Process-wide cap on concurrent shard workers, sized from the JVM system property
     * {@code -Dchat2db.task.shard.total-parallelism} (default 16, matching the per-task
     * adaptive ceiling).
     */
    private static final java.util.concurrent.Semaphore SHARD_GATE = new java.util.concurrent.Semaphore(
            Integer.getInteger("chat2db.task.shard.total-parallelism", 16));

    private boolean tryShardExport(ExportTaskSpec spec, String tableName, TaskExecutionContext context,
            OutputStream output, SinkFactory sinkFactory, ExportValueMode mode,
            SqlExecutionPlan executionPlan, ExportProgressLogger progressLogger) {
        int configuredMax = shardMaxParallelism > 0 ? shardMaxParallelism
                : Math.min(16, Runtime.getRuntime().availableProcessors());
        // SQL dumps shard too: rows are converted to dialect literals on the shard threads and
        // the ordered drain keeps the statements in key order, so the artifact carries the same
        // content as the single-cursor dump while reading with the adaptive fan-out.
        if (configuredMax <= 1 || spec.getCheckpointRows() != null
                || !sqlExecutionPolicyManager.isEmpty()
                || (mode != ExportValueMode.NATIVE && mode != ExportValueMode.SQL_LITERAL)) {
            return false;
        }
        if (!Chat2DBContext.getDbManager().getExportCapability().isKeysetSharding()) {
            return false;
        }
        String keyColumn = singleKeyColumnOrNull(spec, tableName);
        if (keyColumn == null) {
            return false;
        }
        ColumnLayout layout = probeColumnLayout(spec, tableName, keyColumn, executionPlan);
        if (layout == null || layout.keyJdbcIndex <= 0) {
            return false;
        }
        long[] range = probeKeyRange(spec, tableName, keyColumn, layout);
        Long span = range == null ? null : inclusiveKeySpan(range[0], range[1]);
        if (span == null || span < MIN_ROWS_PER_SHARD * 2) {
            return false;
        }
        int workers = (int) Math.max(1L, Math.min((long) configuredMax,
                Math.min(Math.max(1, SHARD_GATE.availablePermits()), span / MIN_ROWS_PER_SHARD)));
        if (workers < 2) {
            return false;
        }
        exportInShards(spec, tableName, context, output, sinkFactory, progressLogger,
                keyColumn, range[0], span, workers, layout, mode);
        progressLogger.queryCompleted("Table data read completed");
        progressLogger.fileFinalizing();
        return true;
    }

    private void exportInShards(ExportTaskSpec spec, String tableName, TaskExecutionContext context,
            OutputStream output, SinkFactory sinkFactory,
            ExportProgressLogger progressLogger, String keyColumn, long lo, long span, int workers,
            ColumnLayout layout, ExportValueMode mode) {
        if (mode == ExportValueMode.SQL_LITERAL && layout.jdbcColumns.isEmpty()) {
            throw new IllegalStateException("SQL export has no authorized columns");
        }
        FormatSink sink = sinkFactory.create(output, spec, tableName, false);
        // Active fan-out is self-tuned inside [2, workers]: the merge loop below submits shards
        // only while the gate admits, and the fixed pool only bounds the threads.
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, workers);
        AdaptiveBatchSizer batchSizer = new AdaptiveBatchSizer(SINK_BATCH_ROWS);
        ShardPagePlan pagePlan = new ShardPagePlan();
        TaskResumeJournal journal = TaskResumeJournal.open(context.taskId(), null);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workers,
                runnable -> {
                    Thread thread = new Thread(runnable, "chat2db-shard-" + context.taskId());
                    thread.setDaemon(true);
                    return thread;
                });
        List<java.util.concurrent.BlockingQueue<Object>> queues = new ArrayList<>(workers);
        java.util.concurrent.atomic.AtomicBoolean abort = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        long mergeStarted = System.nanoTime();
        long drainedRows = 0L;
        try {
            sink.writeSchema(new ExportSchema(layout.columnNames), tableName);
            long step = shardStep(span, workers);
            ai.chat2db.community.tools.model.Context requestContext = ContextUtils.queryContext();
            ConnectInfo callerConnectInfo = Chat2DBContext.getConnectInfo();
            // Drain-driven submission: shard queues are bounded, so the merge must always be
            // draining the oldest submitted shard while later ones run. Gate permits are taken
            // here, before submission, and returned by the worker in its finally; a worker-side
            // acquire would let a later shard hold the only permit while an earlier one waits for
            // it, deadlocking the ordered merge.
            int submitted = 0;
            for (int drained = 0; drained < workers; ) {
                while (submitted < workers && gate.tryAcquire()) {
                    long lowerBound = submitted == 0 ? Long.MIN_VALUE : lo + (long) submitted * step - 1;
                    boolean lastShard = submitted == workers - 1;
                    long end = lastShard ? 0L : lo + (long) (submitted + 1) * step;
                    java.util.concurrent.BlockingQueue<Object> queue =
                            new java.util.concurrent.ArrayBlockingQueue<>(4);
                    queues.add(queue);
                    pool.execute(() -> runShard(spec, tableName, context, layout, keyColumn,
                            lowerBound, end, lastShard, queue, abort, failure, requestContext, callerConnectInfo,
                            gate, batchSizer, pagePlan, mode));
                    submitted++;
                }
                java.util.concurrent.BlockingQueue<Object> queue = queues.get(drained);
                while (true) {
                    Object item = queue.take();
                    if (item == SHARD_END) {
                        break;
                    }
                    @SuppressWarnings("unchecked")
                    List<List<Object>> batch = (List<List<Object>>) item;
                    long bytesBefore = sink.bytesWritten();
                    long writeStarted = System.nanoTime();
                    sink.writeRows(batch);
                    drainedRows += batch.size();
                    batchSizer.record(batch.size(), System.nanoTime() - writeStarted);
                    ExportRateLimiter.global().acquire(batch.size(), sink.bytesWritten() - bytesBefore);
                    progressLogger.recordExportedRows(batch.size());
                }
                drained++;
                if (journal != null && drained % 16 == 0) {
                    journal.progress("EXPORTING", drainedRows);
                }
                if (journal != null && drained % 128 == 0) {
                    journal.snapshot(drainedRows);
                }
            }
            throwShardFailure(failure.get());
            long mergeNanos = System.nanoTime() - mergeStarted;
            lastShardTuningStats = new ShardTuningStats(submitted, workers, gate.availablePermits(),
                    batchSizer.batchSize(), drainedRows, mergeNanos);
            double mergeSeconds = mergeNanos / 1_000_000_000.0D;
            // Final adaptive state: how far the AIMD gate grew/shrank and where the batch sizer
            // settled, for production observability and stress-test reporting.
            log.info("Sharded export finished: shards={}/{}, rows={} in {}s -> {} rows/s, "
                            + "final gate permits={}, final batch size={}",
                    submitted, workers, drainedRows, Math.round(mergeSeconds),
                    mergeSeconds > 0 ? Math.round(drainedRows / mergeSeconds) : 0,
                    gate.availablePermits(), batchSizer.batchSize());
            sink.finishTable(tableName);
            if (journal != null) {
                journal.cleanup();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort.set(true);
            throw new TaskCancelledException();
        } catch (IOException e) {
            throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                    "Could not write export file", e);
        } finally {
            abort.set(true);
            pool.shutdownNow();
            closeSink(sink);
        }
    }

    private void runShard(ExportTaskSpec spec, String tableName, TaskExecutionContext context,
            ColumnLayout layout, String keyColumn, long lowerBound, long end, boolean lastShard,
            java.util.concurrent.BlockingQueue<Object> queue, java.util.concurrent.atomic.AtomicBoolean abort,
            java.util.concurrent.atomic.AtomicReference<Throwable> failure,
            ai.chat2db.community.tools.model.Context requestContext, ConnectInfo callerConnectInfo,
            AdaptiveConcurrencyGate gate, AdaptiveBatchSizer batchSizer, ShardPagePlan pagePlan,
            ExportValueMode mode) {
        boolean permitted = false;
        long shardStarted = System.nanoTime();
        long shardRows = 0L;
        boolean snapshotTx = false;
        // Created on first use and owned by this shard until it exits; copy() carries no
        // connection, so Chat2DBContext.getConnection() builds a dedicated one per shard.
        ConnectInfo isolated = null;
        Thread.currentThread().setName("chat2db-shard-" + context.taskId() + "-" + lowerBound);
        try {
            isolated = callerConnectInfo.copy();
            isolated.setLoginUser("task-" + context.taskId() + "#shard-" + lowerBound);
            MDC.put("taskId", String.valueOf(context.taskId()));
            ContextUtils.setContext(requestContext);
            SHARD_GATE.acquire();
            permitted = true;
            Chat2DBContext.putContext(isolated);
            snapshotTx = startConsistentSnapshotIfEnabled();
            IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            long cursor = lowerBound;
            List<List<Object>> batch = new ArrayList<>(batchSizer.batchSize());
            while (!abort.get()) {
                long pageStarted = System.nanoTime();
                long pageRows = 0L;
                try {
                    String pageSql = Chat2DBContext.getSqlBuilder().dql().buildKeysetPageLimit(
                            KeysetPageLimitRequest.builder()
                                    .databaseName(spec.getTarget().getDatabaseName())
                                    .schemaName(spec.getTarget().getSchemaName())
                                    .tableName(tableName)
                                    .keyColumns(List.of(keyColumn))
                                    .bounds(cursor == Long.MIN_VALUE
                                            ? List.of()
                                            : List.of(new KeyBound(keyColumn, layout.literal(cursor), true)))
                                    .fetchSize(pagePlan.pageSize())
                                    .build());
                    ShardPageResult result = new ShardPageResult();
                    DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), pageSql,
                            pagePlan.pageSize(),
                            resultSet -> readShardRows(resultSet, layout, context, valueProcessor, spec,
                                    tableName, end, lastShard, batch, queue, result, abort, batchSizer, mode),
                            context, () -> {
                                if (abort.get()) {
                                    throw new TaskCancelledException();
                                }
                                context.checkCancelled();
                            });
                    pageRows = result.pageRows;
                    if (result.pageRows == 0 || result.done) {
                        if (result.done) {
                            cursor = result.lastKey;
                            break;
                        }
                        break;
                    }
                    cursor = result.lastKey;
                } finally {
                    // One record per page; the observer excludes the gate wait so it measures
                    // pure read throughput. break still runs this finally, so done pages count too.
                    long pageNanos = System.nanoTime() - pageStarted;
                    gate.record(pageRows, pageNanos);
                    shardRows += pageRows;
                    if (pagePlan.recordPage(pageRows, pageNanos)) {
                        // Source-pressure protection: a too-slow page query means the source is
                        // struggling with the concurrent range scans, so cut the fan-out a
                        // quarter right away; the AIMD window tuning is the recovery path.
                        gate.reduceForSourcePressure();
                    }
                }
            }
            if (!batch.isEmpty()) {
                queue.put(new ArrayList<>(batch));
                batch.clear();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
            abort.set(true);
        } finally {
            if (snapshotTx) {
                try {
                    Chat2DBContext.getConnection().rollback();
                } catch (Throwable rollbackFailure) {
                    log.warn("Shard snapshot transaction rollback failed", rollbackFailure);
                }
            }
            long shardNanos = System.nanoTime() - shardStarted;
            double shardSeconds = shardNanos / 1_000_000_000.0D;
            log.info("Shard export finished: table={}, range=({}, {}], rows={} in {}s -> {} rows/s",
                    tableName, lowerBound, end, shardRows, Math.round(shardSeconds),
                    shardSeconds > 0 ? Math.round(shardRows / shardSeconds) : 0);
            try {
                queue.put(SHARD_END);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (permitted) {
                SHARD_GATE.release();
            }
            // Return the permit taken by the submitting loop; record() above only retunes the
            // available permit count, it never returns a submission permit.
            gate.release();
            if (isolated != null) {
                // Hand the dedicated shard connection back to the pool (or close it) instead of
                // leaking it until the JVM exits.
                ConnectionPool.close(isolated);
            }
            Chat2DBContext.removeContext();
            ContextUtils.removeContext();
            MDC.remove("taskId");
        }
    }

    /**
     * Optionally pins the shard read to one consistent repeatable-read transaction so concurrent
     * source updates cannot tear the shard's pages apart. Off by default (auto-commit reads stay
     * the contract), delegated to the database plugin, and any failure degrades to the plain
     * auto-commit read.
     */
    private boolean startConsistentSnapshotIfEnabled() {
        if (!Boolean.parseBoolean(System.getProperty("chat2db.task.shard.consistent-snapshot", "false"))) {
            return false;
        }
        try {
            return Chat2DBContext.getDbManager()
                    .startConsistentExportSnapshot(Chat2DBContext.getConnection());
        } catch (Throwable snapshotFailure) {
            log.warn("Consistent-snapshot read unavailable; the shard continues on auto-commit",
                    snapshotFailure);
            return false;
        }
    }

    private void readShardRows(ResultSet resultSet, ColumnLayout layout, TaskExecutionContext context,
            IValueProcessor valueProcessor, ExportTaskSpec spec, String tableName, long end,
            boolean lastShard,
            List<List<Object>> batch, java.util.concurrent.BlockingQueue<Object> queue,
            ShardPageResult result, java.util.concurrent.atomic.AtomicBoolean abort,
            AdaptiveBatchSizer batchSizer, ExportValueMode mode) {
        try {
            ResultSetMetaData metaData = resultSet.getMetaData();
            while (resultSet.next()) {
                if (abort.get()) {
                    return;
                }
                context.checkCancelled();
                long key = Long.parseLong(stripQuotes(resultSet.getString(layout.keyJdbcIndex)));
                if (reachedShardEnd(key, end, lastShard)) {
                    result.done = true;
                    return;
                }
                batch.add(readRow(spec, metaData, layout.jdbcColumns, tableName, resultSet,
                        mode, valueProcessor));
                result.lastKey = key;
                result.pageRows++;
                if (batch.size() >= batchSizer.batchSize()) {
                    queue.put(new ArrayList<>(batch));
                    batch.clear();
                }
            }
        } catch (SQLException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private String singleKeyColumnOrNull(ExportTaskSpec spec, String tableName) {
        try {
            List<PrimaryKey> primaryKeys = DefaultSQLExecutor.getInstance().getPrimaryKeys(
                    Chat2DBContext.getConnection(), spec.getTarget().getDatabaseName(),
                    spec.getTarget().getSchemaName(), tableName);
            if (primaryKeys == null || primaryKeys.size() != 1
                    || StringUtils.isBlank(primaryKeys.get(0).getColumnName())) {
                return null;
            }
            return primaryKeys.get(0).getColumnName();
        } catch (Exception e) {
            log.warn("Could not read the primary key for shard export of {}", tableName, e);
            return null;
        }
    }

    /**
     * @return {min, max} of the key, or {@code null} when the table is empty or the key is not numeric
     */
    private long[] probeKeyRange(ExportTaskSpec spec, String tableName, String keyColumn, ColumnLayout layout) {
        String sql = Chat2DBContext.getSqlBuilder().dql().buildSelectKeyRange(
                SelectKeyRangeSqlRequest.builder()
                        .databaseName(spec.getTarget().getDatabaseName())
                        .schemaName(spec.getTarget().getSchemaName())
                        .tableName(tableName)
                        .keyColumn(keyColumn)
                        .build());
        String[] literals = new String[2];
        try {
            DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), sql, 1,
                    resultSet -> {
                        if (resultSet.next()) {
                            literals[0] = resultSet.getString(1);
                            literals[1] = resultSet.getString(2);
                        }
                    },
                    null, null);
        } catch (Exception e) {
            log.warn("Key range probe failed for {}", tableName, e);
            return null;
        }
        if (StringUtils.isBlank(literals[0]) || StringUtils.isBlank(literals[1])) {
            return null;
        }
        layout.keyQuoted = literals[0].startsWith("'");
        try {
            return new long[]{Long.parseLong(stripQuotes(literals[0])), Long.parseLong(stripQuotes(literals[1]))};
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }

    private ColumnLayout probeColumnLayout(ExportTaskSpec spec, String tableName, String keyColumn,
            SqlExecutionPlan executionPlan) {
        ColumnLayout[] holder = new ColumnLayout[1];
        DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(),
                Chat2DBContext.getSqlBuilder().dql().buildKeysetPageLimit(KeysetPageLimitRequest.builder()
                        .databaseName(spec.getTarget().getDatabaseName())
                        .schemaName(spec.getTarget().getSchemaName())
                        .tableName(tableName)
                        .keyColumns(List.of(keyColumn))
                        .fetchSize(1)
                        .build()),
                1, resultSet -> {
                    try {
                        ResultSetMetaData metaData = resultSet.getMetaData();
                        ColumnLayout layout = new ColumnLayout();
                        layout.jdbcColumns = includedJdbcColumns(metaData, executionPlan);
                        layout.columnNames = selectByJdbcIndex(ResultSetUtils.getRsHeader(resultSet),
                                layout.jdbcColumns);
                        for (int index = 0; index < layout.jdbcColumns.size(); index++) {
                            if (keyColumn.equalsIgnoreCase(metaData.getColumnName(layout.jdbcColumns.get(index)))) {
                                layout.keyJdbcIndex = layout.jdbcColumns.get(index);
                            }
                        }
                        holder[0] = layout;
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                },
                null, null);
        return holder[0];
    }

    private static String stripQuotes(String literal) {
        String trimmed = StringUtils.trimToEmpty(literal);
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '\'' && trimmed.endsWith("'")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static RuntimeException toRuntimeException(Throwable t) {
        return t instanceof RuntimeException runtime ? runtime : new RuntimeException(t);
    }

    static Long inclusiveKeySpan(long minimum, long maximum) {
        try {
            return Math.addExact(Math.subtractExact(maximum, minimum), 1L);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    static long shardStep(long span, int workers) {
        return 1L + (span - 1L) / workers;
    }

    static boolean reachedShardEnd(long key, long end, boolean lastShard) {
        return !lastShard && key >= end;
    }

    static void throwShardFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof TaskCancelledException cancelled) {
            throw cancelled;
        }
        throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                "Sharded export failed", toRuntimeException(failure));
    }

    /**
     * Source-protection plan for shard readers. When a keyset page query exceeds the slow-page
     * threshold the page size coarsens (fewer, larger range scans instead of many) and the export
     * gate cuts its fan-out through {@link AdaptiveConcurrencyGate#reduceForSourcePressure()};
     * when pages come back quick again the size walks back to the base. Purely adaptive: every
     * query is an independent keyset page, so resizing between pages is always safe.
     */
    private static final class ShardPagePlan {

        private static final long SLOW_PAGE_NANOS =
                Long.getLong("chat2db.task.shard.slow-page-millis", 500L) * 1_000_000L;

        private final int baseRows;

        private final int maxRows;

        private final java.util.concurrent.atomic.AtomicInteger pageRows =
                new java.util.concurrent.atomic.AtomicInteger();

        ShardPagePlan() {
            this.baseRows = SHARD_PAGE_ROWS;
            this.maxRows = Math.max(SHARD_PAGE_ROWS,
                    Integer.getInteger("chat2db.task.shard.page-rows-max", SHARD_PAGE_ROWS * 4));
            this.pageRows.set(this.baseRows);
        }

        int pageSize() {
            return pageRows.get();
        }

        /** @return whether the page was slow enough to also cut the export fan-out */
        boolean recordPage(long rows, long nanos) {
            try {
                if (rows <= 0 || nanos <= 0) {
                    return false;
                }
                if (nanos > SLOW_PAGE_NANOS) {
                    pageRows.updateAndGet(current -> Math.min(maxRows, current * 2));
                    return true;
                }
                if (nanos < SLOW_PAGE_NANOS / 4) {
                    pageRows.updateAndGet(current -> Math.max(baseRows, current / 2));
                }
                return false;
            } catch (Throwable pageTuningFailure) {
                log.warn("Shard page sizing failed; keeping the current page size",
                        pageTuningFailure);
                return false;
            }
        }
    }

    /** Final adaptive state of one sharded export, for ops dashboards and integration tests. */
    public static final class ShardTuningStats {

        public final int submittedShards;

        public final int workers;

        public final int gatePermits;

        public final int batchSize;

        public final long rows;

        public final long nanos;

        ShardTuningStats(int submittedShards, int workers, int gatePermits, int batchSize,
                long rows, long nanos) {
            this.submittedShards = submittedShards;
            this.workers = workers;
            this.gatePermits = gatePermits;
            this.batchSize = batchSize;
            this.rows = rows;
            this.nanos = nanos;
        }
    }

    private volatile ShardTuningStats lastShardTuningStats;

    /** Final adaptive state of the last sharded export run on this exporter instance. */
    public ShardTuningStats lastShardTuningStats() {
        return lastShardTuningStats;
    }

    /**
     * Immutable per-shard-read settings derived once on the caller thread.
     */
    private static final class ColumnLayout {

        private List<Integer> jdbcColumns = List.of();

        private List<String> columnNames = List.of();

        private int keyJdbcIndex = -1;

        private boolean keyQuoted;

        private String literal(long value) {
            return keyQuoted ? "'" + value + "'" : String.valueOf(value);
        }
    }

    private static final class ShardPageResult {

        private long pageRows;

        private long lastKey;

        private boolean done;
    }

    private void readKeysetRows(ExportTaskSpec spec, String tableName, TaskExecutionContext context,
            OutputStream output, SinkFactory sinkFactory, ExportValueMode mode, SqlExecutionPlan executionPlan,
            ResultSet resultSet, ExportProgressLogger progressLogger, KeysetRun run)
            throws SQLException, IOException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<Integer> jdbcColumns = includedJdbcColumns(metaData, executionPlan);
        if (mode == ExportValueMode.SQL_LITERAL && jdbcColumns.isEmpty()) {
            throw new IllegalStateException("SQL export has no authorized columns");
        }
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        if (run.sink == null) {
            run.keyJdbcIndex = keyJdbcIndex(metaData, jdbcColumns, run.keyColumn);
            if (run.keyJdbcIndex <= 0) {
                throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                        "The checkpoint key column is not part of the exported columns");
            }
            run.sink = sinkFactory.create(output, spec, tableName, run.resuming);
            run.sink.writeSchema(new ExportSchema(
                    selectByJdbcIndex(ResultSetUtils.getRsHeader(resultSet), jdbcColumns)), tableName);
        }
        run.pageRows = 0;
        boolean hasNext = nextRow(resultSet, executionPlan, (int) Math.min(run.rowsDone, Integer.MAX_VALUE));
        while (hasNext) {
            context.checkCancelled();
            run.batch.add(readRow(spec, metaData, jdbcColumns, tableName, resultSet, mode,
                    mode == ExportValueMode.NATIVE ? valueProcessor : null));
            JDBCDataValue keyValue = new JDBCDataValue(resultSet, metaData, run.keyJdbcIndex, false);
            run.cursorLiteral = mode == ExportValueMode.NATIVE
                    ? valueProcessor.getJdbcSqlValueString(keyValue)
                    : sqlLiteral(spec, metaData, run.keyJdbcIndex, tableName, keyValue);
            progressLogger.recordExportedRow();
            run.rowsDone++;
            run.pageRows++;
            hasNext = nextRow(resultSet, executionPlan, (int) Math.min(run.rowsDone, Integer.MAX_VALUE));
            if (run.batch.size() >= run.sizer.batchSize() || !hasNext) {
                flushBatch(run);
            }
        }
    }

    private static int keyJdbcIndex(ResultSetMetaData metaData, List<Integer> jdbcColumns, String keyColumn)
            throws SQLException {
        for (Integer columnIndex : jdbcColumns) {
            if (keyColumn.equalsIgnoreCase(metaData.getColumnName(columnIndex))) {
                return columnIndex;
            }
        }
        return -1;
    }

    private void flushBatch(KeysetRun run) throws IOException {
        if (run.batch.isEmpty()) {
            return;
        }
        long bytesBefore = run.sink.bytesWritten();
        long writeStarted = System.nanoTime();
        run.sink.writeRows(run.batch);
        run.sizer.record(run.batch.size(), System.nanoTime() - writeStarted);
        ExportRateLimiter.global().acquire(run.batch.size(), run.sink.bytesWritten() - bytesBefore);
        run.batch.clear();
    }

    /**
     * Flushes the sink and returns the byte count that is now durable in the artifact file. The
     * counting stream sits directly above the (unbuffered-by-sink) file path and GZIP compression
     * is rejected for checkpointed exports, so the logical count matches the file length exactly.
     */
    private long durableByteCount(KeysetRun run) {
        if (run.sink == null) {
            return 0L;
        }
        try {
            run.sink.flush();
        } catch (IOException e) {
            throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                    "Could not flush the export file before its checkpoint", e);
        }
        return run.sink.bytesWritten();
    }

    private String resumeCursor(TaskExecutionContext context, String keyColumn) {
        return context.resumeStates().stream()
                .filter(state -> state.getShardNo() != null && state.getShardNo() == 0)
                .filter(state -> RESUME_KIND_KEYSET.equals(state.getKind()))
                .filter(state -> StringUtils.isNotBlank(state.getCursorJson()))
                .reduce((first, second) -> second)
                .map(state -> {
                    com.alibaba.fastjson2.JSONObject cursor =
                            com.alibaba.fastjson2.JSON.parseObject(state.getCursorJson());
                    if (!keyColumn.equalsIgnoreCase(cursor.getString("column"))) {
                        throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                                "The stored checkpoint of this export no longer matches its primary key");
                    }
                    return cursor.getString("literal");
                })
                .orElse(null);
    }

    /**
     * Mutable state of one checkpointed export run, shared between the page loop and its consumer.
     */
    private static final class KeysetRun {

        private final String keyColumn;

        private final boolean resuming;

        private final List<List<Object>> batch = new ArrayList<>(SINK_BATCH_ROWS);

        private final AdaptiveBatchSizer sizer;

        private FormatSink sink;

        private String cursorLiteral;

        private int keyJdbcIndex = -1;

        private long rowsDone;

        private long pageRows;

        private KeysetRun(String keyColumn, String resumedCursor, boolean resuming, boolean adaptiveSizing) {
            this.keyColumn = keyColumn;
            this.cursorLiteral = resumedCursor;
            this.resuming = resuming;
            this.sizer = new AdaptiveBatchSizer(SINK_BATCH_ROWS, adaptiveSizing);
        }

        private List<KeyBound> bounds() {
            return cursorLiteral == null
                    ? List.of() : List.of(new KeyBound(keyColumn, cursorLiteral, true));
        }

        private String cursorJson() {
            com.alibaba.fastjson2.JSONObject cursor = new com.alibaba.fastjson2.JSONObject();
            cursor.put("column", keyColumn);
            cursor.put("literal", cursorLiteral);
            return cursor.toJSONString();
        }
    }

    private void streamResultSet(ExportTaskSpec spec, String tableName, TaskExecutionContext context,
            OutputStream output, SinkFactory sinkFactory, ExportValueMode mode, SqlExecutionPlan executionPlan,
            ResultSet resultSet, ExportProgressLogger progressLogger, boolean adaptiveSizing)
            throws SQLException, IOException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<Integer> jdbcColumns = includedJdbcColumns(metaData, executionPlan);
        if (mode == ExportValueMode.SQL_LITERAL && jdbcColumns.isEmpty()) {
            throw new IllegalStateException("SQL export has no authorized columns");
        }
        List<String> columnNames = selectByJdbcIndex(ResultSetUtils.getRsHeader(resultSet), jdbcColumns);
        FormatSink sink = sinkFactory.create(output, spec, tableName, false);
        IValueProcessor valueProcessor = mode == ExportValueMode.NATIVE
                ? Chat2DBContext.getDbMetaData().getValueProcessor() : null;
        List<List<Object>> batch = new ArrayList<>(SINK_BATCH_ROWS);
        AdaptiveBatchSizer batchSizer = new AdaptiveBatchSizer(SINK_BATCH_ROWS, adaptiveSizing);
        int exportedRows = 0;
        try {
            sink.writeSchema(new ExportSchema(columnNames), tableName);
            boolean hasNext = nextRow(resultSet, executionPlan, exportedRows);
            while (hasNext) {
                context.checkCancelled();
                batch.add(readRow(spec, metaData, jdbcColumns, tableName, resultSet, mode, valueProcessor));
                progressLogger.recordExportedRow();
                exportedRows++;
                hasNext = nextRow(resultSet, executionPlan, exportedRows);
                if (batch.size() >= batchSizer.batchSize() || !hasNext) {
                    long bytesBefore = sink.bytesWritten();
                    long writeStarted = System.nanoTime();
                    sink.writeRows(batch);
                    batchSizer.record(batch.size(), System.nanoTime() - writeStarted);
                    ExportRateLimiter.global().acquire(batch.size(), sink.bytesWritten() - bytesBefore);
                    batch.clear();
                }
            }
            sink.finishTable(tableName);
        } finally {
            closeSink(sink);
        }
    }

    private void closeSink(FormatSink sink) {
        try {
            sink.close();
        } catch (IOException | RuntimeException e) {
            log.warn("Export sink failed while closing", e);
        }
    }

    private List<Object> readRow(ExportTaskSpec spec, ResultSetMetaData metaData, List<Integer> jdbcColumns,
            String tableName, ResultSet resultSet, ExportValueMode mode, IValueProcessor valueProcessor)
            throws SQLException {
        List<Object> row = new ArrayList<>(jdbcColumns.size());
        for (Integer columnIndex : jdbcColumns) {
            JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, columnIndex, false);
            if (mode == ExportValueMode.SQL_LITERAL) {
                row.add(sqlLiteral(spec, metaData, columnIndex, tableName, jdbcDataValue));
            } else if (hasExportCellProcessors()) {
                row.add(processJdbcCell(spec, metaData, columnIndex, tableName, jdbcDataValue).getValue());
            } else {
                row.add(valueProcessor.getJdbcValue(jdbcDataValue));
            }
        }
        return row;
    }

    private String sqlLiteral(ExportTaskSpec spec, ResultSetMetaData metaData, int columnIndex, String tableName,
            JDBCDataValue jdbcDataValue) throws SQLException {
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        if (!hasExportCellProcessors()) {
            return valueProcessor.getJdbcSqlValueString(jdbcDataValue);
        }
        ExportCell processedCell = processJdbcCell(spec, metaData, columnIndex, tableName, jdbcDataValue);
        DataType dataType = new DataType();
        dataType.setDataTypeName(processedCell.getTypeName());
        dataType.setPrecision(processedCell.getPrecision());
        dataType.setScale(processedCell.getScale());
        SQLDataValue sqlDataValue = new SQLDataValue();
        sqlDataValue.setDataType(dataType);
        sqlDataValue.setValue(SqlValueSerializer.toSqlLiteral(processedCell.getValue()));
        return valueProcessor.getSqlValueString(sqlDataValue);
    }

    protected String getQuerySql(ExportTaskSpec spec, String tableName) {
        String databaseName = spec.getTarget().getDatabaseName();
        String schemaName = spec.getTarget().getSchemaName();
        return Chat2DBContext.getSqlBuilder().dql().buildSelectTable(databaseName, schemaName, tableName);
    }

    protected SqlExecutionPlan getQueryPlan(ExportTaskSpec spec, String tableName) {
        String querySql = getQuerySql(spec, tableName);
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        SqlExecutionContext context = new SqlExecutionContext(
                spec.getTarget().getDataSourceId(),
                connectInfo == null ? null : connectInfo.getDbType(),
                spec.getTarget().getDatabaseName(),
                spec.getTarget().getSchemaName(),
                tableName, querySql, SqlExecutionOperation.EXPORT, type());
        SqlExecutionPlan plan = sqlExecutionPolicyManager.plan(context);
        sqlExecutionPolicyManager.beforeExecute(plan);
        return plan;
    }

    protected boolean nextRow(ResultSet resultSet, SqlExecutionPlan plan, int exportedRowCount)
            throws SQLException {
        if (!sqlExecutionPolicyManager.isRowAllowed(plan, exportedRowCount)) {
            return false;
        }
        if (exportedRowCount > 0 && exportedRowCount % EXPORT_BATCH_ROWS == 0) {
            sqlExecutionPolicyManager.checkpoint(plan);
        }
        return resultSet.next();
    }

    protected ExportCell processJdbcCell(ExportTaskSpec spec, ResultSetMetaData metaData, int columnIndex,
            String tableName, JDBCDataValue jdbcDataValue) throws SQLException {
        Object value = jdbcDataValue.getObject();
        if (value == null) {
            value = jdbcDataValue.getStringValue();
        }
        ExportCell cell = new ExportCell(value, metaData.getColumnType(columnIndex),
                metaData.getColumnTypeName(columnIndex), metaData.getPrecision(columnIndex),
                metaData.getScale(columnIndex));
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        ExportCellContext cellContext = new ExportCellContext(
                spec.getTarget().getDataSourceId(),
                connectInfo == null ? null : connectInfo.getDbType(),
                spec.getTarget().getDatabaseName(),
                spec.getTarget().getSchemaName(),
                tableName, metaData.getColumnName(columnIndex), type());
        return exportCellProcessorChain.process(cellContext, cell);
    }

    protected List<Integer> includedJdbcColumns(ResultSetMetaData metaData, SqlExecutionPlan plan)
            throws SQLException {
        int columnCount = metaData.getColumnCount();
        List<Integer> includedIndexes = new ArrayList<>(columnCount);
        if (sqlExecutionPolicyManager.isEmpty()) {
            for (int index = 1; index <= columnCount; index++) {
                includedIndexes.add(index);
            }
            return includedIndexes;
        }
        SqlExecutionContext executionContext = plan.getContext();
        for (int index = 1; index <= columnCount; index++) {
            String resultTableName = StringUtils.defaultIfBlank(metaData.getTableName(index),
                    executionContext.getTableName());
            SqlResultColumnContext columnContext = new SqlResultColumnContext(plan, index,
                    metaData.getColumnName(index), metaData.getColumnLabel(index), metaData.getColumnType(index),
                    metaData.getColumnTypeName(index), executionContext.getDatabaseName(),
                    executionContext.getSchemaName(), resultTableName, false);
            if (sqlExecutionPolicyManager.includeColumn(columnContext)) {
                includedIndexes.add(index);
            }
        }
        return includedIndexes;
    }

    protected <T> List<T> selectByJdbcIndex(List<T> values, List<Integer> jdbcColumns) {
        List<T> selected = new ArrayList<>(jdbcColumns.size());
        for (Integer columnIndex : jdbcColumns) {
            int listIndex = columnIndex - 1;
            if (listIndex >= 0 && listIndex < values.size()) {
                selected.add(values.get(listIndex));
            }
        }
        return selected;
    }

    protected boolean hasExportCellProcessors() {
        return !exportCellProcessorChain.isEmpty();
    }

    private String tableProgressMessage(String prefix, String tableName, int tableIndex, int totalTables) {
        return prefix + " " + (tableIndex + 1) + "/" + totalTables + ": " + tableName;
    }

    private void logTableEvent(TaskExecutionContext context, String code, String message, String tableName,
            int tableIndex, int totalTables) {
        context.logInfo(code, message,
                Map.of(TaskConstants.TABLE_NAME_DETAIL_KEY, tableName,
                        TaskConstants.EXPORTED_TABLES_DETAIL_KEY, tableIndex + 1,
                        TaskConstants.TOTAL_TABLES_DETAIL_KEY, totalTables));
    }

    protected abstract void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
            OutputStream output, boolean resuming) throws Exception;

    /**
     * Creates the format sink for one table; called once per table inside the streaming loop.
     * {@code resuming} means the output already holds the earlier pages, so the sink must not emit
     * its leading structure again.
     */
    @FunctionalInterface
    protected interface SinkFactory {

        FormatSink create(OutputStream output, ExportTaskSpec spec, String tableName, boolean resuming);
    }

    /**
     * How raw JDBC values are converted for the sink: native objects, or dialect SQL literal strings.
     */
    protected enum ExportValueMode {
        NATIVE,
        SQL_LITERAL
    }

    /**
     * ZIP entry view that swallows {@code close()} so a sink can finish without ending the entry.
     */
    private static final class EntryStream extends FilterOutputStream {

        private EntryStream(OutputStream zip) {
            super(zip);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
