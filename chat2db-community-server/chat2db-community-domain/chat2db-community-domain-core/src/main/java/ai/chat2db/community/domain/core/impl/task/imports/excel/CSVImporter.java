package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.community.domain.core.impl.task.imports.reader.CsvImportReader;
import ai.chat2db.community.domain.core.impl.task.imports.reader.SourceColumnName;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.excel.support.ExcelTypeEnum;

import java.io.File;
import java.util.List;

/**
 * CSV import through the shared reader, so the header row, data range, delimiter and encoding are
 * decided by the same code the preview used. Importing with different rules than the preview showed
 * is a data bug, not a difference of opinion.
 */
public class CSVImporter extends BaseExcelImporter implements IImportStrategy {

    /** CSV never opens an Excel reader; the method only exists because the base class is shared. */
    @Override
    protected ExcelTypeEnum getExcelType() {
        return ExcelTypeEnum.XLSX;
    }

    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context,
            List<TableColumn> columns) {
        CsvOptions options = (spec.getCsvOptions() == null ? CsvOptions.defaults() : spec.getCsvOptions())
                .validate();
        spec.setCsvOptions(options);
        try (NoModelDataListener listener = new NoModelDataListener(spec, context, columns,
                Chat2DBContext.getDbMetaData().getValueProcessor())) {
            try {
                CsvImportReader.read(new File(spec.getSourceFile()), options, Integer.MAX_VALUE,
                        mappedSourceColumnCount(spec), listener::acceptHead, listener::acceptRow,
                        context::checkCancelled);
                listener.finish("CSV import finished");
            } catch (RuntimeException failure) {
                if (listener.batcher != null) {
                    listener.batcher.abort(failure);
                }
                throw failure;
            }
        }
    }

    /** Highest 1-based source column an explicit mapping names, so a synthetic header covers it. */
    static int mappedSourceColumnCount(ImportTaskSpec spec) {
        if (spec.getColumnMappings() == null) {
            return 0;
        }
        return spec.getColumnMappings().stream()
                .mapToInt(mapping -> SourceColumnName.columnNumber(mapping.getSourceColumn()))
                .max()
                .orElse(0);
    }
}