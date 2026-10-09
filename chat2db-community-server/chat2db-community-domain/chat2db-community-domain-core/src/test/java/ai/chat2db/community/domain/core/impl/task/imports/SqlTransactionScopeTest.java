package ai.chat2db.community.domain.core.impl.task.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the script transaction scope that stops insert batches from committing inside a
 * {@code BEGIN} ... {@code ROLLBACK} script (see the import path in {@link ImportSqlExecutor}).
 */
class SqlTransactionScopeTest {

    @Test
    void classifiesTransactionControlStatementsRegardlessOfCaseAndNoise() {
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("BEGIN"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("begin transaction"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("START TRANSACTION"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("  \n\tbegin  ;"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("-- open it\nBEGIN;"));
        assertEquals(SqlTransactionScope.Effect.BEGIN, SqlTransactionScope.effectOf("/* header */ begin work"));
        assertEquals(SqlTransactionScope.Effect.COMMIT, SqlTransactionScope.effectOf("COMMIT;"));
        assertEquals(SqlTransactionScope.Effect.ROLLBACK, SqlTransactionScope.effectOf("rollback;"));
    }

    @Test
    void ordinaryStatementsAndEmptyInputNeverOpenOrCloseATransaction() {
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf("INSERT INTO t(id) VALUES (1)"));
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf("beginner_report(id)"));
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf("(SELECT 1)"));
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf(""));
        assertEquals(SqlTransactionScope.Effect.NONE, SqlTransactionScope.effectOf(null));
    }

    @Test
    void scopeOpensOnBeginAndClosesOnlyOnCommitOrRollback() {
        SqlTransactionScope scope = new SqlTransactionScope();
        assertFalse(scope.isOpen());

        scope.observe("BEGIN");
        assertTrue(scope.isOpen(), "BEGIN must open the script transaction");

        scope.observe("INSERT INTO t(id) VALUES (1)");
        assertTrue(scope.isOpen(), "a data statement must not close the script transaction");

        scope.observe("ROLLBACK;");
        assertFalse(scope.isOpen(), "ROLLBACK must close the script transaction");

        scope.observe("START TRANSACTION");
        assertTrue(scope.isOpen());
        scope.observe("COMMIT");
        assertFalse(scope.isOpen());
    }

    /**
     * The batch executor must stop managing transactions exactly while the script owns one;
     * otherwise a script ROLLBACK cannot discard the rows its INSERT batches already wrote.
     */
    @Test
    void importExecutorStopsManagingTransactionsWhileTheScriptOwnsOne() {
        ImportSqlExecutor executor = new ImportSqlExecutor(null);
        assertTrue(executor.managesOwnTransaction(), "a script without BEGIN keeps self-managed batches");

        executor.observeExecutedStatement("BEGIN");
        assertFalse(executor.managesOwnTransaction(), "a script BEGIN must suspend batch commits");

        executor.observeExecutedStatement("INSERT INTO t(id) VALUES (1)");
        assertFalse(executor.managesOwnTransaction(), "INSERTs must not re-enable batch commits");

        executor.observeExecutedStatement("ROLLBACK");
        assertTrue(executor.managesOwnTransaction(), "after ROLLBACK the script no longer owns a transaction");
    }
}