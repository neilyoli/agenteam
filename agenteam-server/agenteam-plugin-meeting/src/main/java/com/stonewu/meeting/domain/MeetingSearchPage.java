package com.stonewu.meeting.domain;

import java.util.List;

/** 飞书按会议号返回的会议实例分页结果。 */
public record MeetingSearchPage(List<Meeting> meetings, boolean hasMore, String pageToken) {
    public MeetingSearchPage {
        meetings = meetings == null ? List.of() : List.copyOf(meetings);
    }
}
