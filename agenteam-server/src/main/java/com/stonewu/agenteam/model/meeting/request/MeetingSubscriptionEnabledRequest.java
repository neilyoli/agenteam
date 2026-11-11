package com.stonewu.agenteam.model.meeting.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 启停只改变后续事件，不回溯已经处理的事件。
 */
public record MeetingSubscriptionEnabledRequest(
    @JsonProperty(value = "enabled", required = true) boolean enabled) {
}
