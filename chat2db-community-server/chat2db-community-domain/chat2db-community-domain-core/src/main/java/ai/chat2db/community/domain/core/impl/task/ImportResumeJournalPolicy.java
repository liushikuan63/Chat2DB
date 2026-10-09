package ai.chat2db.community.domain.core.impl.task;

/**
 * Decides what happens to the on-disk resume journal when an import batcher closes.
 *
 * <p>The rule exists because a manifest shard import runs inside one transaction: a batch that
 * succeeded is rolled back together with the shard, so keeping its watermark would make the
 * automatic retry skip rows that were never committed. A flat import, by contrast, commits every
 * batch, so its watermark is truthful and has to survive a later failure.
 */
public final class ImportResumeJournalPolicy {

    /** What the batcher must do with the journal directory when it closes. */
    public enum Outcome {
        /** Rows were committed per batch; keep the watermark so a retry resumes after them. */
        PRESERVE_FAILURE,
        /** Rows are rolled back with the attempt (or everything succeeded); drop the watermark. */
        CLEANUP
    }

    private ImportResumeJournalPolicy() {
    }

    /**
     * @param defersRowDurabilityToCommit whether the enclosing transaction commits after the batcher
     * @param failed whether this attempt ended with a failure
     */
    public static Outcome onClose(boolean defersRowDurabilityToCommit, boolean failed) {
        return failed && !defersRowDurabilityToCommit ? Outcome.PRESERVE_FAILURE : Outcome.CLEANUP;
    }

    /**
     * Applies the decision to a journal. A null journal means the state path was unusable, which
     * the batcher already treats as "no journal".
     */
    public static void apply(TaskResumeJournal journal, boolean defersRowDurabilityToCommit, boolean failed,
            long rowsDone) {
        if (journal == null) {
            return;
        }
        if (onClose(defersRowDurabilityToCommit, failed) == Outcome.PRESERVE_FAILURE) {
            journal.progress("FAILED", rowsDone);
            journal.preserve();
        } else {
            journal.cleanup();
        }
    }
}