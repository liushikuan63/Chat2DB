package ai.chat2db.community.domain.core.impl.task;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Destructive integration fixtures must be explicitly named and restricted to loopback. */
public record RequiredDatabaseFixture(String host, int port, String user, String password, String database) {
    public static RequiredDatabaseFixture load(String flavor, int defaultPort) {
        String prefix = "C2D_" + flavor + "_";
        String password = System.getenv(prefix + "PASSWORD");
        boolean configured = password != null && !password.isBlank();
        if (Boolean.parseBoolean(System.getenv("C2D_IT_REQUIRED"))) {
            assertTrue(configured, prefix + "PASSWORD is required by the integration gate");
        } else {
            assumeTrue(configured, prefix + "PASSWORD absent; optional integration fixture unavailable");
        }
        String host = value(prefix + "HOST", "127.0.0.1");
        assertTrue(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"),
                "Integration fixture host must be loopback");
        String database = value(prefix + "DB", "c2d_task_it_" + flavor.toLowerCase(java.util.Locale.ROOT));
        assertTrue(database.matches("c2d_task_it_[a-z0-9_]{1,40}"),
                "Refusing destructive fixture setup outside c2d_task_it_* allowlist");
        int port = Integer.parseInt(value(prefix + "PORT", String.valueOf(defaultPort)));
        assertTrue(port > 0 && port <= 65535, "Invalid fixture port");
        return new RequiredDatabaseFixture(host, port, value(prefix + "USER", "root"), password, database);
    }

    private static String value(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public String toString() {
        return "RequiredDatabaseFixture[" + host + ":" + port + "/" + database + "]";
    }
}
