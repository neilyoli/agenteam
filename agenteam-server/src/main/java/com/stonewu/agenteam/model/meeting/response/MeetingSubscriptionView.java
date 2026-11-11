package com.stonewu.agenteam.model.meeting.response;

/**
 * 订阅声明的公开视图，不包含凭据或内部事件正文。
 */
public record MeetingSubscriptionView(String id, String enterpriseId, String integrationId, String eventType,
                                      String status, String hireId, String agentVersionId, String pluginVersion,
                                      String ownerUserId, long revision, String createdAt, String updatedAt) {
}
