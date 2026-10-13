package ai.chat2db.community.domain.core.impl.task.imports.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.chat2db.community.tools.exception.ParamBusinessException;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A JSON key that matches no column must stop the import. Matching only by exact case would turn the
 * user's data into NULLs while the task still reports success.
 */
class JSONImporterKeyMatchingTest {

    private static final Map<Integer, String> TARGET_COLUMNS = columns("name", "city");

    @Test
    void keysThatDifferOnlyInCaseStillResolveToTheColumn() {
        Map<String, Integer> indexByName = JSONImporter.targetIndexByName(TARGET_COLUMNS);

        assertEquals(0, indexByName.get(JSONImporter.normalizeKey("name")));
        assertEquals(1, indexByName.get(JSONImporter.normalizeKey("CITY")),
                "a case difference is a naming habit, not an error");
        assertDoesNotThrow(() -> JSONImporter.requireKnownKeys(
                java.util.Set.of("Name", "CITY"), indexByName.keySet(), 1));
    }

    @Test
    void anUnknownColumnFailsTheImportInsteadOfWritingNull() {
        Map<String, Integer> indexByName = JSONImporter.targetIndexByName(TARGET_COLUMNS);

        ParamBusinessException failure = assertThrows(ParamBusinessException.class,
                () -> JSONImporter.requireKnownKeys(
                        java.util.Set.of("Name", "Nickname"), indexByName.keySet(), 1));
        assertEquals("import.json.unknownColumns[Nickname]@1", failure.getArgs()[0],
                "the failure must name the offending key and row so the user can fix the file");
    }

    @Test
    void emptyAndBlankKeysAreTreatedAsUnmapped() {
        assertEquals("", JSONImporter.normalizeKey(null));
        assertEquals("", JSONImporter.normalizeKey("   "));
        assertEquals("city", JSONImporter.normalizeKey(" City "));
    }

    private static Map<Integer, String> columns(String... names) {
        Map<Integer, String> header = new LinkedHashMap<>();
        for (int index = 0; index < names.length; index++) {
            header.put(index, names[index]);
        }
        return header;
    }
}
