package ai.chat2db.community.domain.core.impl.task.executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;

import org.junit.jupiter.api.Test;

/**
 * A task that asked for staging-only behaviour (rehearsal, full rollback, validation, finalization)
 * must never fall through to the plain importer, which would commit the rows it promised to test.
 */
class DataFileRoutingStagingGuardTest {

    @Test
    void stagingRequestWithoutAManifestFailsInsteadOfImportingForReal() {
        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> DataFileImportTaskExecutor.requireManifestForStagingRequest(true, false));

        assertEquals(TaskErrorCode.IMPORT_FAILED.name(), failure.getCode());
    }

    @Test
    void plainImportIsUnaffected() {
        assertDoesNotThrow(() -> DataFileImportTaskExecutor.requireManifestForStagingRequest(false, false));
        assertDoesNotThrow(() -> DataFileImportTaskExecutor.requireManifestForStagingRequest(false, true));
    }

    @Test
    void stagingRequestWithAManifestIsUnaffected() {
        assertDoesNotThrow(() -> DataFileImportTaskExecutor.requireManifestForStagingRequest(true, true));
    }

    @Test
    void rehearsalRequestIsRecognisedAsAStagingFeature() {
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .rollbackOptions(ai.chat2db.community.domain.api.model.task.ImportRollbackOptions.builder()
                        .rehearsal(Boolean.TRUE).build())
                .build();

        assertEquals(true, DataFileImportTaskExecutor.stagingFeaturesRequested(spec),
                "a rehearsal request must be routed as a staging feature so the guard can see it");
    }

    @Test
    void aPlainSpecRequestsNoStagingFeatures() {
        assertEquals(false, DataFileImportTaskExecutor.stagingFeaturesRequested(ImportTaskSpec.builder().build()));
        assertEquals(false, DataFileImportTaskExecutor.stagingFeaturesRequested(null));
    }
}