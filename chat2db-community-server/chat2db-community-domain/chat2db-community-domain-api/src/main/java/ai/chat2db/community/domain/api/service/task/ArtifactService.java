package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/** Manages task output files and their temporary staging paths. */
public interface ArtifactService {
    default ArtifactDraft createDraft(Long taskId, String outputDirectory, String fileName, String mediaType) {
        return createDraft(taskId, TaskArtifactRole.OUTPUT, outputDirectory, fileName, mediaType);
    }

    ArtifactDraft createDraft(Long taskId, String role, String outputDirectory, String fileName, String mediaType);

    ArtifactDraft resumeDraft(Long taskId, String role, String outputDirectory, String fileName,
            String mediaType, File existingTemporaryFile);

    boolean isInterruptedDraft(Long taskId, File file);

    String publish(ArtifactDraft draft);

    void deleteDraft(ArtifactDraft draft);

    void deletePublished(String artifactId);

    /** Stages a file only when the destination is absent; an already staged file is left in place. */
    void stageForDeletion(Path original, Path staged) throws IOException;

    default boolean cleanupInterruptedArtifact(Long taskId, String temporaryPath, String publishedPath) {
        return cleanupInterruptedArtifacts(taskId, Collections.singletonList(temporaryPath),
                Collections.singletonList(publishedPath));
    }

    boolean cleanupInterruptedArtifacts(Long taskId, List<String> temporaryPaths, List<String> publishedPaths);
}
