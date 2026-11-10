package com.stonewu.agenteam.model.meeting.response;

/** 督办扫描作业视图：每次 Schedule 发生的扫描结果与候选/催办摘要。 */
public record MeetingFollowUpScanView(
    String id,
    String enterpriseId,
    String bindingId,
    String scheduleId,
    String occurrenceId,
    String status,
    int attemptCount,
    int maxAttempts,
    String nextAttemptAt,
    String lastErrorCode,
    String result,
    String createdAt,
    String updatedAt) {
}
