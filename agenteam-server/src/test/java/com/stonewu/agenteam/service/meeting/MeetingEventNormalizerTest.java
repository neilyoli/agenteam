package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** 会议事件识别只依赖信封结构，不需要数据库或平台凭据。 */
class MeetingEventNormalizerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MeetingEventNormalizer normalizer = new MeetingEventNormalizer();
    private final IntegrationApplication app = new IntegrationApplication("app-1", "ent-1", "feishu", "tenant-1",
        "cli_abc", 1L);
    private final Instant receivedAt = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void recognizesPrefixedMeetingEventAndExtractsIdentifiers() throws Exception {
        var envelope = json.readTree("""
            {"header":{"event_type":"meeting.advanced_meeting_started","event_id":"evt-1","app_id":"cli_abc","tenant_key":"tenant-1"},
             "event":{"meeting":{"meeting_id":"meeting-1","meeting_source":1},"minutes_token":"minute-1"}}
            """);
        var result = normalizer.normalize(app, envelope, receivedAt);
        assertTrue(result.isPresent());
        var event = result.get();
        assertEquals("meeting.advanced_meeting_started", event.eventType());
        assertEquals("evt-1", event.eventId());
        assertEquals("meeting-1", event.meetingId());
        assertEquals("minute-1", event.minuteToken());
        assertEquals(receivedAt, event.receivedAt());
    }

    @Test
    void rejectsNonCalendarMeetingSource() throws Exception {
        var envelope = json.readTree("""
            {"header":{"event_type":"meeting.ended","event_id":"evt-call","app_id":"cli_abc"},
             "event":{"meeting":{"meeting_id":"meeting-3","meeting_source":2}}}
            """);
        assertTrue(normalizer.normalize(app, envelope, receivedAt).isEmpty());
    }

    @Test
    void rejectsMissingEventId() throws Exception {
        var envelope = json.readTree("""
            {"header":{"event_type":"meeting.ended","app_id":"cli_abc"},
             "event":{"meeting":{"meeting_id":"meeting-4","meeting_source":1}}}
            """);
        var failure = assertThrows(ApiException.class, () -> normalizer.normalize(app, envelope, receivedAt));
        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertEquals("MEETING_EVENT_ID_MISSING", failure.code());
    }

    @Test
    void recognizesMinutesPrefixAndFallsBackToEventLevelFields() throws Exception {
        var envelope = json.readTree("""
            {"event":{"event_type":"minutes.meeting_record_created","event_id":"evt-2","meeting_id":"meeting-2",
                      "minute_token":"minute-2","app_id":"cli_abc","tenant_key":"tenant-1"}}
            """);
        var result = normalizer.normalize(app, envelope, receivedAt);
        assertTrue(result.isPresent());
        assertEquals("minutes.meeting_record_created", result.get().eventType());
        assertEquals("meeting-2", result.get().meetingId());
        assertEquals("minute-2", result.get().minuteToken());
    }

    @Test
    void rejectsNonMeetingEventsWithoutPersisting() throws Exception {
        var envelope = json.readTree("""
            {"header":{"event_type":"im.message.receive_v1","event_id":"evt-3"}}
            """);
        assertTrue(normalizer.normalize(app, envelope, receivedAt).isEmpty());
    }

    @Test
    void rejectsBlankType() throws Exception {
        var envelope = json.readTree("{}");
        assertTrue(normalizer.normalize(app, envelope, receivedAt).isEmpty());
    }

    @Test
    void rejectsApplicationMismatchFromHeader() throws Exception {
        var envelope = json.readTree("""
            {"header":{"event_type":"meeting.started","event_id":"evt-4","app_id":"cli_other"}}
            """);
        var failure = assertThrows(ApiException.class, () -> normalizer.normalize(app, envelope, receivedAt));
        assertEquals(HttpStatus.FORBIDDEN, failure.getStatusCode());
        assertEquals("MEETING_APPLICATION_MISMATCH", failure.code());
    }

    @Test
    void rejectsTenantMismatchFromEventBody() throws Exception {
        var envelope = json.readTree("""
            {"header":{"event_type":"meeting.started","event_id":"evt-5"},
             "event":{"tenant_key":"tenant_other"}}
            """);
        var failure = assertThrows(ApiException.class, () -> normalizer.normalize(app, envelope, receivedAt));
        assertEquals("MEETING_APPLICATION_MISMATCH", failure.code());
    }

    @Test
    void acceptsMatchingScopeOnBothHeaderAndBody() throws Exception {
        var envelope = json.readTree("""
            {"header":{"event_type":"meeting.started","event_id":"evt-6","app_id":"cli_abc"},
             "event":{"tenant_key":"tenant-1"}}
            """);
        assertTrue(normalizer.normalize(app, envelope, receivedAt).isPresent());
    }
}
