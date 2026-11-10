package com.stonewu.agenteam.model.meeting.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 字段绑定写入：映射为业务字段编码到飞书字段名，服务端按白名单与真实 schema 校验。
 */
public record MeetingBindingWriteRequest(
    @NotBlank(message = "请填写必填项。") @Size(max = 100, message = "文字长度不符合要求。") String integrationId,
    @NotBlank(message = "请填写必填项。") @Size(max = 160, message = "文字长度不符合要求。") String name,
    @NotBlank(message = "请填写必填项。") @Size(max = 191, message = "文字长度不符合要求。") String appToken,
    @NotBlank(message = "请填写必填项。") @Size(max = 191, message = "文字长度不符合要求。") String tableId,
    @NotBlank(message = "请填写必填项。") @Size(max = 32, message = "文字长度不符合要求。") String businessType,
    Map<String, String> fieldMapping,
    Map<String, String> writeback,
    @JsonProperty(value = "enabled", required = true) boolean enabled) {
}
