package com.stonewu.agenteam.model.meeting.response;

/** 催办台账视图：逐任务的限频状态与来源证据，供管理员核对“提醒不重复轰炸”。 */
public record MeetingFollowUpView(
    String id,
    String enterpriseId,
    String bindingId,
    String businessKey,
    String ownerSubject,
    String lastReminderAt,
    String nextReminderAt,
    int reminderCount,
    String lastEvidence,
    String status,
    String createdAt,
    String updatedAt) {
}
