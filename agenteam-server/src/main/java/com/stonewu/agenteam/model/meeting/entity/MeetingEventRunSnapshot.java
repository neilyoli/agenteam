package com.stonewu.agenteam.model.meeting.entity;

/**
 * 会议事件 Run 创建时固定的订阅与来源快照。执行期授权只与该快照比对，
 * 不按当前最新订阅猜测原配置，防止订阅被修改后旧 Run 仍获得新权限。
 */
public record MeetingEventRunSnapshot(
    String subscriptionId,
    String hireId,
    String pluginVersion,
    String agentVersionId,
    String integrationId,
    String selectedVersionId) {
}
