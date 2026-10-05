package ai.chat2db.community.domain.core.impl.task.imports.json;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.JsonOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowSqlBuilder;
import ai.chat2db.community.domain.core.impl.task.imports.ImportSqlExecutor;
import ai.chat2db.community.domain.core.impl.task.imports.reader.ImportCell;
import ai.chat2db.community.domain.core.impl.task.imports.reader.JsonImportReader;
import ai.chat2db.community.tools.exception.ParamBusinessException;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * JSON import. Document keys are the column names, but a user may write {@code Name} where the table
 * column is {@code name}, so keys are matched case-insensitively. A key that matches no column stops
 * the import instead of becoming a silent NULL: a task that reports success while dropping the
 * user's data is worse than one that fails with a reason the user can act on.
 */
public class JSONImporter extends BaseImporter {

    private static final int BATCH_SIZE = 1000;

    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        JsonOptions options = (spec.getJsonOptions() == null ? new JsonOptions() : spec.getJsonOptions()).validate();
        spec.setJsonOptions(options);
        ImportRowSqlBuilder builder = new ImportRowSqlBuilder(spec, columns);
        ImportSqlExecutor executor = new ImportSqlExecutor(context);
        List<String> names = spec.getColumnMappings() == null
                ? columns.stream().map(TableColumn::getName).toList()
                : spec.getColumnMappings().stream().map(mapping -> mapping.getSourceColumn()).toList();
        Map<Integer, String> header = new LinkedHashMap<>();
        for (int index = 0; index < names.size(); index++) {
            header.put(index, names.get(index));
        }
        builder.acceptHead(header);
        Map<String, Integer> targetIndexByName = targetIndexByName(header);
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        JsonImportReader.read(new File(spec.getSourceFile()), options, Integer.MAX_VALUE,
                (Map<String, ImportCell> record, Integer rowNumber) -> {
            requireKnownKeys(record.keySet(), targetIndexByName.keySet(), rowNumber);
            Map<Integer, ImportCell> cells = new LinkedHashMap<>();
            record.forEach((key, value) -> {
                Integer index = targetIndexByName.get(normalizeKey(key));
                if (index != null) {
                    cells.put(index, value);
                }
            });
            batch.add(builder.buildCells(cells, rowNumber));
            if (batch.size() >= BATCH_SIZE) {
                executor.executeBatch(batch);
                batch.clear();
            }
        }, context::checkCancelled);
        executor.executeBatch(batch);
    }

    /** Normalized target column name -> its position in the header the row builder works with. */
    static Map<String, Integer> targetIndexByName(Map<Integer, String> header) {
        Map<String, Integer> index = new TreeMap<>();
        header.forEach((position, name) -> index.put(normalizeKey(name), position));
        return index;
    }

    static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    static void requireKnownKeys(Set<String> sourceKeys, Set<String> targetNames, int rowNumber) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : sourceKeys) {
            if (!targetNames.contains(normalizeKey(key))) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            // ParamBusinessException only carries one argument, so the offending keys travel in the
            // first slot; the message pattern gets the list and the row number appended.
            throw new ParamBusinessException(
                    "import.json.unknownColumns[" + String.join(", ", unknown) + "]@" + rowNumber);
        }
    }
}
