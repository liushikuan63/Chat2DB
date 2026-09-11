package ai.chat2db.community.domain.api.model.task;

/**
 * How a resumed import treats a row whose key already exists in the target (duplicate or unique
 * constraint violation). Only a resumed run consults this: a fresh import keeps the historical
 * behaviour, because there a duplicate is genuine bad data rather than evidence that an earlier
 * run already applied the row.
 */
public enum ResumeDuplicatePolicy {

    /**
     * Record the row as already applied: it is listed in a dedicated reconciliation artifact and
     * an INFO event, the row is not counted against {@code maxErrors}, and the import completes.
     * The default, because a resume exists precisely to finish what an interrupted run started.
     */
    RECONCILE,

    /** Count the row as a rejected row, subject to {@code maxErrors} (the historical behaviour). */
    REJECT,

    /** Abort the task on the first already-applied row, like {@code ABORT} on any other error. */
    FAIL
}
