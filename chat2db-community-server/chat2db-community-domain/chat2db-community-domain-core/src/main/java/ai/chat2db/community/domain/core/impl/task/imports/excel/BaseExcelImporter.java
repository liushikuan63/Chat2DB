package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ExcelOptions;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportColumnResolver;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowBatcher;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.util.ConverterUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * XLSX/XLS import through EasyExcel, sharing the column resolution, batching and reject handling
 * with the CSV path.
 */
@Slf4j
public abstract class BaseExcelImporter extends BaseImporter {

    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        ExcelTypeEnum excelType = getExcelType();
        // Materialise the validated defaults so the row builder reads the same values it will act
        // on, even when the client sent no Excel options at all (as CSV does for CsvOptions).
        spec.setExcelOptions(spec.getExcelOptions() == null
                ? new ExcelOptions().validate()
                : spec.getExcelOptions().validate());
        ExcelOptions excelOptions = spec.getExcelOptions();
        try (NoModelDataListener listener = new NoModelDataListener(spec, context, columns,
                Chat2DBContext.getDbMetaData().getValueProcessor())) {
            try {
                EasyExcel.read(new File(spec.getSourceFile()), listener)
                        .excelType(excelType)
                        .sheet(excelOptions.getSheetIndex())
                        .headRowNumber(excelOptions.getHasHeader() ? excelOptions.getHeaderRow() : 0)
                        .doRead();
                context.checkCancelled();
                listener.finish("Excel import finished");
            } catch (RuntimeException failure) {
                if (listener.batcher != null) {
                    listener.batcher.abort(failure);
                }
                throw failure;
            }
        }
    }

    protected abstract ExcelTypeEnum getExcelType();

    public class NoModelDataListener extends AnalysisEventListener<Map<Integer, String>> implements AutoCloseable {

        private final ImportTaskSpec spec;

        private final TaskExecutionContext taskContext;

        private final List<TableColumn> columns;

        private final IValueProcessor valueProcessor;

        private ImportColumnResolver.Resolution resolution;

        protected ImportRowBatcher batcher;

        private long rowNumber;

        private final long startedNanos;

        protected NoModelDataListener(ImportTaskSpec spec, TaskExecutionContext taskContext,
                List<TableColumn> columns, IValueProcessor valueProcessor) {
            this.startedNanos = System.nanoTime();
            this.spec = spec;
            this.taskContext = taskContext;
            this.columns = columns;
            this.valueProcessor = valueProcessor;
        }

        @Override
        public void invokeHead(Map<Integer, ReadCellData<?>> headCells, AnalysisContext context) {
            this.taskContext.checkCancelled();
            Map<Integer, String> headMap = ConverterUtils.convertToStringMap(headCells, context);
            List<String> headers = new ArrayList<>();
            int columnsCount = headMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
            for (int index = 0; index < columnsCount; index++) {
                headers.add(headMap.getOrDefault(index, ""));
            }
            resolution = ImportColumnResolver.resolveForSpec(columns, headers, spec);
            reportResolution(taskContext, resolution);
            ImportColumnResolver.validateForImport(columns, resolution, spec);
            batcher = new ImportRowBatcher(spec, taskContext, resolution, valueProcessor);
        }

        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            this.taskContext.checkCancelled();
            if (data == null || data.isEmpty() || batcher == null || resolution == null) {
                return;
            }
            int width = resolution.matches().size();
            List<String> values = new ArrayList<>(width);
            for (int index = 0; index < width; index++) {
                values.add(data.get(index));
            }
            batcher.accept(++rowNumber, values);
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            this.taskContext.checkCancelled();
        }

        void finish(String summaryMessage) {
            if (batcher == null) {
                return;
            }
            batcher.flush();
            taskContext.logInfo("IMPORT_SUMMARY", summaryMessage, Map.of(
                    "importedRows", batcher.importedRows(),
                    "rejectedRows", batcher.rejectedRows(),
                    "elapsedMillis", (System.nanoTime() - startedNanos) / 1_000_000L));
        }

        /** Resolves the columns and opens the batcher from a header row read outside EasyExcel. */
        void acceptHead(Map<Integer, String> header) {
            this.taskContext.checkCancelled();
            int columnsCount = header.isEmpty() ? 0
                    : java.util.Collections.max(header.keySet()) + 1;
            List<String> headers = new ArrayList<>(columnsCount);
            for (int index = 0; index < columnsCount; index++) {
                headers.add(header.get(index));
            }
            resolution = ImportColumnResolver.resolveForSpec(columns, headers, spec);
            reportResolution(taskContext, resolution);
            ImportColumnResolver.validateForImport(columns, resolution, spec);
            batcher = new ImportRowBatcher(spec, taskContext, resolution, valueProcessor);
        }

        /**
         * Feeds one data row read outside EasyExcel.
         *
         * <p>The row is numbered by its position among the data rows, exactly like {@link #invoke},
         * not by its line in the file. The number is the batcher's watermark unit: it drives resume,
         * the progress message ("imported N rows") and the reject log, all of which mean "the Nth
         * row of data". Passing the physical line number instead would make a file with a header
         * report one row more than it imported.
         */
        void acceptRow(Map<Integer, String> row, int currentRow) {
            this.taskContext.checkCancelled();
            if (row == null || row.isEmpty() || batcher == null || resolution == null) {
                return;
            }
            int width = resolution.matches().size();
            List<String> values = new ArrayList<>(width);
            for (int index = 0; index < width; index++) {
                values.add(row.get(index));
            }
            batcher.accept(++rowNumber, values);
        }

        @Override
        public void close() {
            if (batcher != null) {
                batcher.close();
            }
        }
    }
}
