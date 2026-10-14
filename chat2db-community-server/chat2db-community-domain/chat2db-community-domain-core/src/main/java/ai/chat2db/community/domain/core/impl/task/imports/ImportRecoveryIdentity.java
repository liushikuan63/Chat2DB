package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.fastjson2.JSON;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Stable server/source identity for the standard, single-table receipt protocol. */
public final class ImportRecoveryIdentity {
    public static final String DATABASE = "DATABASE_RECEIPTS_V1";
    public static final String UNSUPPORTED = "NO_DATABASE_RECEIPTS";
    private ImportRecoveryIdentity() { }

    public static boolean eligible(ImportTaskSpec spec) {
        return !TaskExecutionMode.isUltraFast(spec.getMode())
                && (spec.getScope() == null || "TABLE".equalsIgnoreCase(spec.getScope()))
                && (spec.getTableSources() == null || spec.getTableSources().isEmpty())
                && !Boolean.TRUE.equals(spec.getManifestShard()) && spec.getStagingPolicy() == null
                && spec.getValidationOptions() == null && spec.getFinalizationOptions() == null
                && spec.getRollbackOptions() == null;
    }

    public static String runKey(ImportTaskSpec spec) {
        if (spec.getRecoveryIdentity() == null) throw new IllegalArgumentException("Missing server recovery identity");
        java.util.UUID.fromString(spec.getRecoveryIdentity());
        return ImportReceiptStore.hash(spec.getRecoveryIdentity() + "\n"
                + Path.of(spec.getSourceFile()).toAbsolutePath().normalize() + "\n"
                + JSON.toJSONString(spec.getTarget()));
    }

    public static ImportReceiptStore open(ImportTaskSpec spec, ImportColumnResolver.Resolution resolution,
            TaskExecutionContext context) {
        if (!DATABASE.equals(spec.getRecoveryProtocol())) return null;
        if (!eligible(spec) || context.defersRowDurabilityToCommit()) {
            throw new ImportReceiptStore.ReceiptSafetyException("IMPORT_RECEIPT_UNSAFE",
                    "This task cannot use the standard single-table receipt protocol", null);
        }
        var dialect = Chat2DBContext.getDbMetaData().importReceiptDialect().orElseThrow(() ->
                new ImportReceiptStore.ReceiptSafetyException("IMPORT_RECEIPT_UNSAFE",
                        "The target plugin no longer supports database import receipts", null));
        Map<String,Object> identity = new TreeMap<>();
        identity.put("sourceHash", sourceHash(Path.of(spec.getSourceFile()), context));
        identity.put("format", spec.getFormat()); identity.put("options", spec.getOptions());
        identity.put("csv", spec.getCsvOptions()); identity.put("excel", spec.getExcelOptions());
        identity.put("json", spec.getJsonOptions()); identity.put("timeFormat", spec.getDataTimeFormat());
        identity.put("unmappedTarget", spec.getUnmappedTarget()); identity.put("resolution", resolution);
        return ImportReceiptStore.open(dialect, Chat2DBContext.getConnectInfo(), spec.getTarget().getTableName(),
                runKey(spec), ImportReceiptStore.hash(JSON.toJSONString(identity)), context);
    }

    private static String sourceHash(Path source, TaskExecutionContext context) {
        try (var input = Files.newInputStream(source)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                context.checkCancelled(); digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new ImportReceiptStore.ReceiptSafetyException("IMPORT_RECEIPT_UNSAFE",
                    "Could not bind the import receipt to its complete source", failure);
        }
    }
}
