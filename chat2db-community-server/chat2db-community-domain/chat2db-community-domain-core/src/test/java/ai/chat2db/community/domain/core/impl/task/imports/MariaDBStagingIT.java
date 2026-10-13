package ai.chat2db.community.domain.core.impl.task.imports;

class MariaDBStagingIT extends RealDialectStagingITSupport {
    @Override protected boolean postgres() { return false; }
}
