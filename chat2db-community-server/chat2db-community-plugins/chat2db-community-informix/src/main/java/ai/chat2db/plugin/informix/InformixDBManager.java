package ai.chat2db.plugin.informix;

import ai.chat2db.plugin.generic.GenericDBManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;

@Slf4j
public class InformixDBManager extends GenericDBManager implements IDbManager {

    /**
     * This plugin relies on no session, replication or trigger catalog object, and the exact
     * sysmaster view names for session limits and triggers have not been verified against a live
     * instance, so this probe issues no unverified query and reports the unknown with its reason
     * instead of guessing SQL. Informix also exposes no server disk free space through SQL.
     */
    @Override
    public ai.chat2db.spi.model.imports.ImportResourceSnapshot probeImportResources(Connection connection,
            String databaseName, String schemaName) {
        return ai.chat2db.spi.model.imports.ImportResourceSnapshot.unknown(
                "Informix: this plugin relies on no session, replication or trigger catalog object, and "
                        + "the sysmaster view names for session limits and triggers are not verified "
                        + "against a live instance, so this probe issues no unverified queries; server "
                        + "disk free space is not exposed through Informix SQL");
    }

    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        String url = connectInfo.getUrl();
        String service = connectInfo.getServiceName();
        if (StringUtils.isNotBlank(service) && !StringUtils.contains(url, "INFORMIXSERVER=")) {
            connectInfo.setUrl(url + ":" + "INFORMIXSERVER=" + service);
        }
        return super.getConnection(connectInfo);
    }
}
