package ai.chat2db.plugin.oceanbase.oracle;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the parallel keyset export contract this plugin declares: keyset-sharded exports are
 * enabled, so table exports may be split into key ranges and read in parallel.
 */
class OceanbaseOracleDBManagerExportCapabilityTest {

    @Test
    void declaresKeysetShardingExportCapability() {
        assertTrue(new OceanbaseOracleDBManager().getExportCapability().isKeysetSharding());
    }

    @Test
    void startsConsistentSnapshotWithDialectOwnedStatements() throws Exception {
        List<String> preparedSql = new ArrayList<>();
        PreparedStatement statement = (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        Connection connection = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        preparedSql.add((String) args[0]);
                        return statement;
                    }
                    return defaultValue(method.getReturnType());
                });

        assertTrue(new OceanbaseOracleDBManager().startConsistentExportSnapshot(connection));
        assertEquals(List.of("SET TRANSACTION READ ONLY"), preparedSql);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }
}
