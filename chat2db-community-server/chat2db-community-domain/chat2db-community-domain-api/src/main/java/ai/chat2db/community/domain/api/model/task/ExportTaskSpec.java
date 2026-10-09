package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportTaskSpec implements TaskSpec {

    private String taskType;

    private String taskName;

    private TaskTargetSnapshot target;

    private List<String> tableNames;

    private String sql;

    private String originalSql;

    private Integer resultSetId;

    private String exportSize;

    private String format;

    private String scope;

    private Boolean containData;

    private Boolean containsHeader;

    private String exportPath;

    private String suggestedFileName;

    /**
     * Execution mode the client asked for. Export only implements the conservative path today, so a
     * parallel mode is rejected instead of being silently downgraded to a standard export.
     */
    private String mode;

    /** Output compression the client asked for; absent means the writer default. */
    private String compression;
}
