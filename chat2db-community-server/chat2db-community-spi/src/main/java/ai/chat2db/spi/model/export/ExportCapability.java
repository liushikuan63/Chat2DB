package ai.chat2db.spi.model.export;

/**
 * Self-described table-export abilities of one database, declared by its plugin.
 *
 * <p>{@code keysetSharding} means the dialect supports {@code WHERE pk > ? ORDER BY pk} style
 * paging so a table export can be split into parallel key ranges. Plugins declare this instead of
 * the core branching on database type strings.
 */
public final class ExportCapability {

    private final boolean keysetSharding;

    private ExportCapability(boolean keysetSharding) {
        this.keysetSharding = keysetSharding;
    }

    /**
     * A plugin whose keyset SQL and connection semantics have been verified for parallel export.
     */
    public static final ExportCapability KEYSET_SHARDING = new ExportCapability(true);

    /**
     * The conservative default for plugins that have not opted into parallel keyset export.
     */
    public static final ExportCapability SERIAL_ONLY = new ExportCapability(false);

    public boolean isKeysetSharding() {
        return keysetSharding;
    }

}
