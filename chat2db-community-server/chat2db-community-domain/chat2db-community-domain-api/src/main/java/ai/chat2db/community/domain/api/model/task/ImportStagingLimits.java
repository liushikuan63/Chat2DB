package ai.chat2db.community.domain.api.model.task;

/** Configured capacity, not remaining free space or a reservation. All sizes are bytes. */
public record ImportStagingLimits(long maxFileBytes, long maxTotalBytes, int maxFiles) { }
