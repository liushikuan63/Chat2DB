package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One queued task deletion. A task may own several files (a primary export plus reject summaries and
 * import reports), so every artifact is staged and removed; the legacy single-path fields are still
 * read so a queue file written by an earlier release keeps working.
 */
@Data
@NoArgsConstructor
class PendingTaskDeletion {
    private Long taskId;
    private List<String> originalPaths = new ArrayList<>();
    private List<String> stagedPaths = new ArrayList<>();
    /** Legacy single-artifact entry written before multi-artifact tasks existed. */
    private String originalPath;
    /** Legacy single-artifact entry written before multi-artifact tasks existed. */
    private String stagedPath;
    private int attempts;
    private String lastError;

    static PendingTaskDeletion create(Task task, List<TaskArtifact> artifacts) {
        PendingTaskDeletion deletion = new PendingTaskDeletion();
        deletion.taskId = task.getId();
        for (String artifactPath : artifactPaths(task, artifacts)) {
            Path original = Path.of(artifactPath).toAbsolutePath().normalize();
            deletion.originalPaths.add(original.toString());
            deletion.stagedPaths.add(original.resolveSibling("." + original.getFileName()
                    + ".task-delete-" + UUID.randomUUID()).toString());
        }
        if (deletion.stagedPaths.size() == 1) {
            // Keep the single-artifact shape readable for an older queue consumer.
            deletion.originalPath = deletion.originalPaths.get(0);
            deletion.stagedPath = deletion.stagedPaths.get(0);
        }
        return deletion;
    }

    static PendingTaskDeletion create(Task task) {
        return create(task, List.of());
    }

    /** The task's own primary artifact plus every tracked artifact, without duplicates. */
    private static List<String> artifactPaths(Task task, List<TaskArtifact> artifacts) {
        List<String> paths = new ArrayList<>();
        if (StringUtils.isNotBlank(task.getArtifactId())) {
            paths.add(task.getArtifactId());
        }
        if (artifacts != null) {
            for (TaskArtifact artifact : artifacts) {
                if (artifact != null && StringUtils.isNotBlank(artifact.getArtifactId())
                        && !paths.contains(artifact.getArtifactId())) {
                    paths.add(artifact.getArtifactId());
                }
            }
        }
        return paths;
    }

    void beginAttempt() {
        attempts++;
        lastError = null;
    }

    void recordFailure(Exception error) {
        lastError = error.toString();
    }

    boolean hasArtifact() {
        return !stagedFiles().isEmpty();
    }

    List<Path> originalFiles() {
        return toPaths(getOriginalPaths(), originalPath);
    }

    List<Path> stagedFiles() {
        return toPaths(getStagedPaths(), stagedPath);
    }

    /** The staged copy of the primary download, or {@code null} when the primary is not staged. */
    Path primaryStagedFile() {
        if (StringUtils.isBlank(stagedPath)) {
            return stagedFiles().isEmpty() ? null : stagedFiles().get(0);
        }
        return Path.of(stagedPath);
    }

    /** Legacy single-path view; the primary artifact for a task that still owns one file. */
    Path stagedFile() {
        return primaryStagedFile();
    }

    /** Legacy single-path view of the original file behind {@link #stagedFile()}. */
    Path originalFile() {
        List<Path> originals = originalFiles();
        return originals.isEmpty() ? null : originals.get(0);
    }

    private static List<Path> toPaths(List<String> values, String legacy) {
        List<Path> paths = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (StringUtils.isNotBlank(value)) {
                    paths.add(Path.of(value));
                }
            }
        }
        if (paths.isEmpty() && StringUtils.isNotBlank(legacy)) {
            paths.add(Path.of(legacy));
        }
        return paths;
    }
}