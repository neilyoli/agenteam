package com.stonewu.meeting.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一场会议的外部快照。所有字段为来源事实，缺失时保持空值，不补造。
 */
public record Meeting(
    String meetingId,
    String meetingNo,
    String topic,
    String hostOpenId,
    String hostName,
    List<MeetingParticipant> participants,
    Instant startTime,
    Instant endTime,
    String calendarType,
    Integer status) {

    /** 会议没有明确时间时返回 true，需标记待澄清。 */
    public boolean missingTime() {
        return startTime == null || endTime == null;
    }
}
