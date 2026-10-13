package ai.chat2db.community.domain.core.impl.file;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ImportStagingLimitsTest {
    @TempDir Path directory;

    @Test
    void advertisedFileLimitIsTheActualServerAdmissionLimit() throws Exception {
        withProperties(Map.of("max-bytes", "4", "max-total-bytes", "12", "max-files", "2"), () -> {
            var service = new ImportFileStagingService();
            var limits = service.uploadLimits();
            assertEquals(4, limits.maxFileBytes());
            assertEquals(12, limits.maxTotalBytes());
            assertEquals(2, limits.maxFiles());
            var file = Files.writeString(directory.resolve("oversize.csv"), "12345");
            assertThrows(BusinessException.class, () -> service.stage(file.toFile(), "oversize.csv"));
        });
    }

    @Test
    void invalidConfigurationUsesTheSamePositiveDefaultsAsAdmission() throws Exception {
        withProperties(Map.of("max-bytes", "-1", "max-total-bytes", "0", "max-files", "invalid"), () -> {
            var limits = new ImportFileStagingService().uploadLimits();
            assertEquals(1024L * 1024 * 1024, limits.maxFileBytes());
            assertEquals(10L * 1024 * 1024 * 1024, limits.maxTotalBytes());
            assertEquals(1000, limits.maxFiles());
        });
    }

    private void withProperties(Map<String, String> values, Checked action) throws Exception {
        Map<String, String> original = new HashMap<>();
        values.forEach((key, value) -> {
            String name = "chat2db.task.import.staging." + key;
            original.put(name, System.getProperty(name));
            System.setProperty(name, value);
        });
        try { action.run(); }
        finally { original.forEach((key, value) -> {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
        }); }
    }
    private interface Checked { void run() throws Exception; }
}
