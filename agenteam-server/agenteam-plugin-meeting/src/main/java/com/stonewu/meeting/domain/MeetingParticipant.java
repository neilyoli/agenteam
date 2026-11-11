package com.stonewu.meeting.domain;

/**
 * 会议参会人。身份以来源提供的稳定标识为准；姓名仅作展示，不用于身份判断。
 */
public record MeetingParticipant(
    String openId,
    String unionId,
    String name,
    String userId,
    Boolean isHost,
    String role) {
}
