package com.stonewu.agenteam.model.meeting.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 启停只影响后续投递；停用后待投递命令保留，不删除历史。 */
public record MeetingBindingEnabledRequest(
    @JsonProperty(value = "enabled", required = true) boolean enabled) {
}
