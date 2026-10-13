package ai.chat2db.community.domain.api.model.task;

import lombok.Builder;
import lombok.Data;

/** Current control capability and acknowledgement; cancellation never promises a data rollback. */
@Data
@Builder
public class TaskControlState {
    private Long taskId;
    private String status;
    private boolean cancelSupported;
    private boolean cancellationRequested;
    private boolean pauseSupported;
    private String reason;
    private String pauseReason;
}
