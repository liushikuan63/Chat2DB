package ai.chat2db.community.web.api.converter.task;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskImportRequestCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final TaskWebConverter converter = new TaskWebConverter();

    @Test
    void legacyCsvOptionsReachTheExecutionSpec() throws Exception {
        ImportTaskSpec spec = convert("CSV", "\"csvOptions\":{\"delimiter\":\";\",\"encoding\":\"ISO-8859-1\","
                + "\"headerRow\":2,\"dataStartRow\":3,\"emptyAsNull\":false}");
        assertNotNull(spec.getCsvOptions(), "legacy CSV options must not be silently discarded");
        assertEquals(";", spec.getCsvOptions().getDelimiter());
        assertEquals("ISO-8859-1", spec.getCsvOptions().getEncoding());
        assertEquals(2, spec.getCsvOptions().getHeaderRow());
        assertEquals(3, spec.getCsvOptions().getDataStartRow());
        assertEquals(false, spec.getCsvOptions().getEmptyAsNull());
    }

    @Test
    void legacyExcelSheetAndRangeReachTheExecutionSpec() throws Exception {
        ImportTaskSpec spec = convert("XLSX", "\"excelOptions\":{\"sheetIndex\":2,\"headerRow\":3,\"dataStartRow\":4}");
        assertNotNull(spec.getExcelOptions());
        assertEquals(2, spec.getExcelOptions().getSheetIndex());
        assertEquals(3, spec.getExcelOptions().getHeaderRow());
        assertEquals(4, spec.getExcelOptions().getDataStartRow());
    }

    @Test
    void legacyJsonStructureReachesTheExecutionSpec() throws Exception {
        ImportTaskSpec spec = convert("JSON", "\"jsonOptions\":{\"structure\":\"LINES\",\"emptyAsNull\":true}");
        assertNotNull(spec.getJsonOptions());
        assertEquals("LINES", spec.getJsonOptions().getStructure());
        assertEquals(true, spec.getJsonOptions().getEmptyAsNull());
    }

    @Test
    void legacySqlEncodingReachesTheExecutionSpec() throws Exception {
        ImportTaskSpec spec = convert("SQL", "\"sqlImportOptions\":{\"encoding\":\"ISO-8859-1\"}");
        assertNotNull(spec.getSqlImportOptions());
        assertEquals("ISO-8859-1", spec.getSqlImportOptions().getEncoding());
    }

    @Test
    void invalidLegacyOptionsAreRejectedBeforeSubmission() {
        assertThrows(BusinessException.class, () -> convert("CSV", "\"csvOptions\":{\"delimiter\":\"||\"}"));
        assertThrows(BusinessException.class, () -> convert("XLSX", "\"excelOptions\":{\"sheetIndex\":-1}"));
        assertThrows(BusinessException.class, () -> convert("JSON", "\"jsonOptions\":{\"structure\":\"INVALID\"}"));
        assertThrows(BusinessException.class, () -> convert("SQL", "\"sqlImportOptions\":{\"encoding\":\"NOT-A-CHARSET\"}"));
    }

    @Test
    void unrelatedFormatOptionsAreNotApplied() throws Exception {
        ImportTaskSpec spec = convert("JSON", "\"csvOptions\":{\"delimiter\":\"||\"},\"jsonOptions\":{\"structure\":\"LINES\"}");
        assertNull(spec.getCsvOptions());
        assertNotNull(spec.getJsonOptions());
        assertEquals("LINES", spec.getJsonOptions().getStructure());
    }

    @Test
    void richOptionsAreNotShadowedByInjectedLegacyCsvDefaults() throws Exception {
        ImportTaskSpec spec = convert("CSV", "\"options\":{\"delimiter\":\";\",\"onError\":\"SKIP\"}");
        assertNull(spec.getCsvOptions());
        assertNotNull(spec.getOptions());
        assertEquals(";", spec.getOptions().getDelimiter());
        assertEquals("SKIP", spec.getOptions().getOnError());
    }

    private ImportTaskSpec convert(String format, String options) throws Exception {
        String json = "{\"dataSourceId\":1,\"tableName\":\"orders\",\"sourceFile\":\"fixture.dat\",\"format\":\""
                + format + "\"," + options + "}";
        return converter.importRequest2spec(mapper.readValue(json, TaskImportRequest.class));
    }
}
