package ai.chat2db.spi.sql.builder;

import ai.chat2db.community.domain.api.model.sql.OrderBy;
import ai.chat2db.spi.model.request.KeysetPageLimitRequest;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.SelectKeyRangeSqlRequest;

import java.util.List;

public interface IDqlSqlBuilder {

    String buildSelectTable(String databaseName, String schemaName, String tableName);

    String buildSelectCount(String databaseName, String schemaName, String tableName);

    String buildPageLimit(PageLimitRequest pageLimitRequest);

    /**
     * Keyset (seek) page: rows strictly after the {@code bounds} cursor in key order, limited to
     * {@code fetchSize} rows. Empty bounds produce the plain ordered cursor query.
     */
    default String buildKeysetPageLimit(KeysetPageLimitRequest keysetPageLimitRequest) {
        throw new UnsupportedOperationException("Keyset pagination is not supported by this SQL builder");
    }

    /**
     * {@code SELECT MIN(key), MAX(key) FROM table} used to derive shard boundaries.
     */
    default String buildSelectKeyRange(SelectKeyRangeSqlRequest selectKeyRangeSqlRequest) {
        throw new UnsupportedOperationException("Key range queries are not supported by this SQL builder");
    }

    String buildOrderBy(String originSql, List<OrderBy> orderByList);

    String buildExplain(String sql);
}
