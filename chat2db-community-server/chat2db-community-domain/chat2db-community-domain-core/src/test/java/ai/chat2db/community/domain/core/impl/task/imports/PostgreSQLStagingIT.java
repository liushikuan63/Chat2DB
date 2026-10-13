package ai.chat2db.community.domain.core.impl.task.imports;

class PostgreSQLStagingIT extends RealDialectStagingITSupport {
    @Override protected boolean postgres() { return true; }
}
