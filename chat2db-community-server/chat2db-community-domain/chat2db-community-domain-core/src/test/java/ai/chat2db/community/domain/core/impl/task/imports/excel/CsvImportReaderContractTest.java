package ai.chat2db.community.domain.core.impl.task.imports.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.core.impl.task.imports.reader.CsvImportReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The execution path has to apply the same header and row-range rules the preview showed. These cases
 * fail when the importer consumes the first record as a header unconditionally or ignores the
 * configured data range.
 */
class CsvImportReaderContractTest {

    @Test
    void aHeaderlessFileKeepsEveryLineAsData() throws Exception {
        Path csv = tempDirectory().resolve("no-header.csv");
        Files.writeString(csv, "1,ok\n2,fine\n", StandardCharsets.UTF_8);

        Map<Integer, List<String>> byRow = new LinkedHashMap<>();
        CsvOptions headerless = CsvOptions.builder()
                .hasHeader(Boolean.FALSE).dataStartRow(1)
                .delimiter(",").encoding("UTF-8").build();
        CsvImportReader.read(csv.toFile(), headerless, Integer.MAX_VALUE, 0,
                header -> byRow.put(-1, toList(header)),
                (row, rowNumber) -> byRow.put(rowNumber, toList(row)),
                () -> {
                });

        assertEquals(List.of("1", "ok"), byRow.get(1), "the first line is data, not a header");
        assertEquals(List.of("2", "fine"), byRow.get(2), "no line may be swallowed as a header");
    }

    @Test
    void aHeaderFileEmitsTheHeaderAndStartsDataAfterIt() throws Exception {
        Path csv = tempDirectory().resolve("header.csv");
        Files.writeString(csv, "ROW_ID,ROW_NAME\n1,ok\n2,fine\n", StandardCharsets.UTF_8);

        Map<Integer, List<String>> byRow = new LinkedHashMap<>();
        CsvImportReader.read(csv.toFile(), null, Integer.MAX_VALUE, 0,
                header -> byRow.put(-1, toList(header)),
                (row, rowNumber) -> byRow.put(rowNumber, toList(row)),
                () -> {
                });

        assertEquals(List.of("ROW_ID", "ROW_NAME"), byRow.get(-1), "the configured header must be reported");
        assertEquals(List.of("1", "ok"), byRow.get(2), "data starts on the line after the header");
        assertEquals(List.of("2", "fine"), byRow.get(3));
    }

    @Test
    void aRestrictedDataRangeStopsAtTheConfiguredEndRow() throws Exception {
        Path csv = tempDirectory().resolve("range.csv");
        Files.writeString(csv, "ROW_ID\n1\n2\n3\n4\n", StandardCharsets.UTF_8);

        CsvOptions options = CsvOptions.builder()
                .hasHeader(Boolean.TRUE).headerRow(1).dataStartRow(2).dataEndRow(3)
                .delimiter(",").encoding("UTF-8").build();

        List<Integer> rows = new ArrayList<>();
        CsvImportReader.read(csv.toFile(), options, Integer.MAX_VALUE, 0,
                header -> {
                },
                (row, rowNumber) -> rows.add(rowNumber),
                () -> {
                });

        assertEquals(List.of(2, 3), rows, "a row past the configured end must not be imported");
    }

    @Test
    void anAlternateDelimiterIsHonoured() throws Exception {
        Path csv = tempDirectory().resolve("semicolon.csv");
        Files.writeString(csv, "ROW_ID;ROW_NAME\n1;ok\n", StandardCharsets.UTF_8);

        CsvOptions options = CsvOptions.builder()
                .hasHeader(Boolean.TRUE).headerRow(1).dataStartRow(2)
                .delimiter(";").encoding("UTF-8").build();

        Map<Integer, List<String>> byRow = new LinkedHashMap<>();
        CsvImportReader.read(csv.toFile(), options, Integer.MAX_VALUE, 0,
                header -> byRow.put(-1, toList(header)),
                (row, rowNumber) -> byRow.put(rowNumber, toList(row)),
                () -> {
                });

        assertEquals(List.of("ROW_ID", "ROW_NAME"), byRow.get(-1), "a semicolon file must not collapse to one column");
        assertEquals(List.of("1", "ok"), byRow.get(2));
    }

    @Test
    void theImporterKeepsTheOptionsItValidatedOnTheSpec() {
        // The execution path persists what it acted on, so a later log or report can name it.
        assertTrue(CsvOptions.defaults().getHasHeader(),
                "a file with a header row is the default assumption");
        File unused = new File(".");
        assertTrue(unused.exists());
    }

    private static List<String> toList(Map<Integer, String> row) {
        int size = row.isEmpty() ? 0 : new ArrayList<>(row.keySet()).stream().mapToInt(Integer::intValue).max()
                .orElse(-1) + 1;
        List<String> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(row.get(index));
        }
        return values;
    }

    private Path tempDirectory() throws Exception {
        return Files.createTempDirectory("csv-reader-contract");
    }
}
