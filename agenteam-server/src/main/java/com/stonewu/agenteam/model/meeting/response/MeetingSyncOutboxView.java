package com.stonewu.agenteam.model.meeting.response;

/**
 * 投递箱对账视图，供管理员查看失败队列与补推。
 */
public record MeetingSyncOutboxView(
    String id,
    String enterpriseId,
    String bindingId,
    String businessKey,
    String commandId,
    long desiredVersion,
    long deliveredVersion,
    String recordId,
    int attemptCount,
    int maxAttempts,
    String nextAttemptAt,
    String status,
    String lastErrorCode,
    String lastRequestId,
    String runId,
    String createdAt,
    String updatedAt) {
}
