package ai.chat2db.community.domain.core.impl.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Proves the resume journal is dropped exactly when the enclosing transaction rolls the attempt
 * back, so a retry cannot skip rows that were discarded.
 */
class ImportResumeJournalPolicyTest {

    @Test
    void rolledBackAttemptMustNotKeepAWatermark() throws IOException {
        Path directory = TaskResumeJournal.directoryFor(999_001L).toPath();
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(directory.toFile(),
                Map.of("sourcePath", "shard-1.csv"));
        assertNotNull(journal, "an open journal is the precondition for this scenario");
        journal.progress("IMPORTING", 500L);
        assertTrue(Files.exists(directory), "a progress record must exist before the attempt fails");

        // The shard rolls its transaction back, so those 500 rows no longer exist.
        ImportResumeJournalPolicy.apply(journal, true, true, 500L);

        assertFalse(Files.exists(directory),
                "a rolled-back attempt must not leave a watermark that would skip discarded rows");
    }

    @Test
    void selfCommittingFailureKeepsItsWatermark() throws IOException {
        Path directory = TaskResumeJournal.directoryFor(999_002L).toPath();
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(directory.toFile(),
                Map.of("sourcePath", "flat.csv"));
        assertNotNull(journal);
        journal.progress("IMPORTING", 500L);

        // These batches already committed, so the watermark is truthful and must survive.
        ImportResumeJournalPolicy.apply(journal, false, true, 500L);

        assertTrue(Files.exists(directory), "committed batches must stay resumable after a later failure");
        assertEquals(500L, TaskResumeJournal.recoverTail(directory.toFile()).orElseThrow().rowsDone(),
                "the surviving watermark must still describe the committed rows");
    }

    @Test
    void aSuccessfulRunAlwaysCleansUp() throws IOException {
        Path directory = TaskResumeJournal.directoryFor(999_003L).toPath();
        TaskResumeJournal journal = TaskResumeJournal.openDirectory(directory.toFile(),
                Map.of("sourcePath", "done.csv"));
        assertNotNull(journal);
        journal.progress("IMPORTING", 500L);

        ImportResumeJournalPolicy.apply(journal, false, false, 500L);

        assertFalse(Files.exists(directory), "a completed import has nothing left to resume");
    }

    @Test
    void decisionsCoverEveryCombination() {
        assertEquals(ImportResumeJournalPolicy.Outcome.PRESERVE_FAILURE,
                ImportResumeJournalPolicy.onClose(false, true));
        assertEquals(ImportResumeJournalPolicy.Outcome.CLEANUP,
                ImportResumeJournalPolicy.onClose(false, false));
        assertEquals(ImportResumeJournalPolicy.Outcome.CLEANUP,
                ImportResumeJournalPolicy.onClose(true, true));
        assertEquals(ImportResumeJournalPolicy.Outcome.CLEANUP,
                ImportResumeJournalPolicy.onClose(true, false));
    }

    @Test
    void aNullJournalIsTolerated() {
        ImportResumeJournalPolicy.apply(null, true, true, 10L);
        ImportResumeJournalPolicy.apply(null, false, true, 10L);
    }
}