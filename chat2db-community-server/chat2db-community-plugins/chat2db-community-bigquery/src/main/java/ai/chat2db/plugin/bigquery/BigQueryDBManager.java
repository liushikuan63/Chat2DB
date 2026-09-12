package ai.chat2db.plugin.bigquery;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
public class BigQueryDBManager extends DefaultDBManager implements IDbManager {

    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.KEYSET_SHARDING;
    }

    /**
     * BigQuery is serverless: there is no fixed connection pool or replication lag to read, and it
     * has no row-level DML triggers. Its job-metadata views would need a project- and
     * region-qualified name whose exact syntax has not been verified against a live instance, so
     * this probe deliberately issues no unverified query and reports the unknown with its reason.
     */
    @Override
    public ai.chat2db.spi.model.imports.ImportResourceSnapshot probeImportResources(Connection connection,
            String databaseName, String schemaName) {
        return ai.chat2db.spi.model.imports.ImportResourceSnapshot.unknown(
                "BigQuery is serverless, so there is no fixed connection pool or replication lag to "
                        + "read and no row-level DML triggers; its job views require a project- and "
                        + "region-qualified name whose exact syntax is not verified against a live "
                        + "instance, and this probe does not issue unverified queries; server storage "
                        + "capacity is not exposed as a per-import admission fact");
    }

    /**
     * Keys this manager injects into extendInfo. A reconnect reuses the same
     * ConnectInfo instance, so these must be stripped first to avoid duplicates.
     */
    private static final Set<String> MANAGED_EXTEND_INFO_KEYS =
            Set.of("ProjectId", "OAuthServiceAcctEmail", "OAuthType", "OAuthPvtKeyPath");

    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        List<KeyValue> keyValues = prepareExtendInfo(connectInfo);
        connectInfo.setExtendInfo(keyValues);
        return super.getConnection(connectInfo);
    }

    static List<KeyValue> prepareExtendInfo(ConnectInfo connectInfo) {
        List<KeyValue> keyValues = connectInfo.getExtendInfo() == null
                ? new ArrayList<>()
                : new ArrayList<>(connectInfo.getExtendInfo());
        keyValues.removeIf(kv -> kv != null && kv.getKey() != null
                && MANAGED_EXTEND_INFO_KEYS.contains(kv.getKey()));
        if(StringUtils.isNotBlank(connectInfo.getProject())){
            KeyValue keyValue = new KeyValue();
            keyValue.setKey("ProjectId");
            keyValue.setValue(connectInfo.getProject());
            keyValues.add(keyValue);
        }
        if(StringUtils.isNotBlank(connectInfo.getEmail())){
            KeyValue keyValue = new KeyValue();
            keyValue.setKey("OAuthServiceAcctEmail");
            keyValue.setValue(connectInfo.getEmail());
            keyValues.add(keyValue);
        }
        if(StringUtils.isNotBlank(connectInfo.getKeyfile())){
            KeyValue keyValue = new KeyValue();
            keyValue.setKey("OAuthType");
            keyValue.setValue("0");
            keyValues.add(keyValue);

            KeyValue keyValue1 = new KeyValue();
            keyValue1.setKey("OAuthPvtKeyPath");
            keyValue1.setValue(connectInfo.getKeyfile());
            keyValues.add(keyValue1);
        }
        return keyValues;
    }

}
