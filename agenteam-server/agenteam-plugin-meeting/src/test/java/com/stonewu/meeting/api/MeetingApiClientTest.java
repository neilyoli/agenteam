package com.stonewu.meeting.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 路径段编码：普通令牌原样返回，特殊字符按百分号编码，避免 ISO 时间、分页游标或特殊字符破坏请求。 */
class MeetingApiClientTest {

    @Test
    void keepsPlainIdentifiersUnchanged() {
        assertEquals("6911188411934433028", MeetingApiClient.encode("6911188411934433028"));
        assertEquals("obcn_abc123", MeetingApiClient.encode("obcn_abc123"));
    }

    @Test
    void encodesSpacesAndReservedCharacters() {
        assertEquals("a%20b", MeetingApiClient.encode("a b"));
        assertEquals("%2F%3F%23", MeetingApiClient.encode("/?#"));
        assertEquals("%E4%BC%9A%E8%AE%AE", MeetingApiClient.encode("会议"));
    }

    @Test
    void buildsDocumentedMeetingSearchAndDetailPaths() {
        assertEquals("/open-apis/vc/v1/meetings/list_by_no?meeting_no=123456789",
            MeetingApiClient.meetingSearchPath("meeting_no=123456789"));
        assertEquals("/open-apis/vc/v1/meetings/meeting%2Fid?with_participants=true&with_meeting_ability=false&user_id_type=open_id",
            MeetingApiClient.meetingDetailPath("meeting/id"));
    }

    @Test
    void returnsEmptyForNull() {
        assertEquals("", MeetingApiClient.encode(null));
    }
}
