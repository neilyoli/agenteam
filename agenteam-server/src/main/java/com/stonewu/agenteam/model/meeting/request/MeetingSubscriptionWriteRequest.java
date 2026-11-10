package com.stonewu.agenteam.model.meeting.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 订阅声明固定飞书应用、事件类型与会议分析雇佣；插件版本由服务端按当前安装解析。
 */
public record MeetingSubscriptionWriteRequest(
    @NotBlank(message = "请填写必填项。") @Size(max = 100, message = "文字长度不符合要求。") String integrationId,
    @NotBlank(message = "请填写必填项。") @Size(max = 80, message = "文字长度不符合要求。") String eventType,
    @NotBlank(message = "请填写必填项。") @Size(max = 100, message = "文字长度不符合要求。") String hireId,
    @Size(max = 100, message = "文字长度不符合要求。") String agentVersionId,
    @JsonProperty(value = "enabled", required = true) boolean enabled) {
}
