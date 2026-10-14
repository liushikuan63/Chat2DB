package ai.chat2db.community.storage.task;

import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2TaskStorageDurabilityTest {

    @TempDir
    Path directory;

    @Test
    void acknowledgedCheckpointSurvivesAbruptProcessExit() throws Exception {
        Path result = directory.resolve("created-task.txt");
        Path log = directory.resolve("writer.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(java))) {
            java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        }
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process writer = new ProcessBuilder(java, "-cp", classpath, CrashWriter.class.getName(),
                directory.toString(), result.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(writer.waitFor(30, TimeUnit.SECONDS), "owned checkpoint writer timed out");
            assertEquals(0, writer.exitValue(), () -> read(log));
            Long taskId = Long.valueOf(Files.readString(result));
            try (H2TaskStorage reopened = new H2TaskStorage(directory.toString())) {
                assertTrue(reopened.get(taskId).isPresent(), "the previously synced task must survive");
                var checkpoints = reopened.listResumeStates(taskId);
                assertEquals(1, checkpoints.size(),
                        "a checkpoint acknowledged by saveResumeState must survive without close/shutdown hooks");
                assertEquals(500L, checkpoints.get(0).getRowsDone());
            }
        } finally {
            if (writer.isAlive()) {
                writer.destroyForcibly();
                writer.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static String read(Path file) {
        try { return Files.readString(file); }
        catch (Exception failure) { return failure.getClass().getSimpleName(); }
    }

    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            TaskDatabase database = new TaskDatabase(args[0]);
            H2TaskStorage storage = new H2TaskStorage(database);
            Task task = storage.create(Task.builder().type("DATA_FILE_IMPORT").name("durability-fixture")
                    .specJson("{}").build(), TaskEvent.builder().level("INFO").code("TASK_CREATED").build());
            // Sync the seed separately so the regression discriminates checkpoint durability.
            try (Connection connection = database.open(); Statement statement = connection.createStatement()) {
                statement.execute("CHECKPOINT SYNC");
            }
            storage.saveResumeState(task.getId(), ResumeState.builder().shardNo(0).kind("IMPORT_ROW")
                    .rowsDone(500L).cursorJson("{}").build());
            Files.writeString(Path.of(args[1]), task.getId().toString());
            // Deliberately bypass JDBC close and every shutdown hook in this owned child JVM.
            Runtime.getRuntime().halt(0);
        }
    }
}
