package com.stonewu.agenteam.model.meeting.entity;

import java.time.Instant;

/** 从飞书会议事件信封提取的规范化标识，不含纪要正文。 */
public record NormalizedMeetingEvent(String eventType, String eventId, String meetingId, String minuteToken,
                                     Instant receivedAt) {
}
