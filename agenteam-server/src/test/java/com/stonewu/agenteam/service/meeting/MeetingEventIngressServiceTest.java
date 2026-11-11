package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingEventMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSubscriptionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRow;
import com.stonewu.agenteam.model.meeting.entity.NormalizedMeetingEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 会议事件入口：资料就绪事件快进等待中的会议事件，其余事件按去重后落收件箱并排队。 */
class MeetingEventIngressServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final MeetingEventNormalizer normalizer = mock(MeetingEventNormalizer.class);
    private final MeetingSubscriptionMapper subscriptions = mock(MeetingSubscriptionMapper.class);
    private final MeetingEventMapper events = mock(MeetingEventMapper.class);
    private final MeetingEventStore store = mock(MeetingEventStore.class);
    private final EnterpriseMapper enterprises = mock(EnterpriseMapper.class);
    private final BackgroundJobMapper jobs = mock(BackgroundJobMapper.class);
    private final ResourceJson json = new ResourceJson(new ObjectMapper());
    private final JsonNode envelope = json.read("{}");

    private final IntegrationApplication app = new IntegrationApplication("app-1", "ent-1", "feishu", "tenant-1",
        "cli_abc", 1L);

    private final MeetingEventIngressService service =
        new MeetingEventIngressService(normalizer, subscriptions, events, store, enterprises, jobs, json, CLOCK);

    @Test
    void fastForwardsWaitingEventWhenMinuteTokenArrives() {
        when(normalizer.normalize(app, envelope, NOW)).thenReturn(Optional.of(
            new NormalizedMeetingEvent("minutes.meeting_record_created", "evt-1", "meeting-1", "minute-1", NOW)));
        when(enterprises.lockEnterprise("ent-1")).thenReturn(Optional.of("ent-1"));
        var waiting = waitingRow();
        when(store.waitingForMeeting("ent-1", "app-1", "meeting-1")).thenReturn(waiting);

        assertTrue(service.accept("ent-1", app, envelope));

        verify(store).requeue(waiting, "minute-1");
        verify(jobs).release("job-1", NOW);
        verify(events, never()).insert(any(MeetingEventRow.class));
        verify(jobs, never()).enqueue(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            any());
    }

    @Test
    void enqueuesWhenNoWaitingEventAndSubscribed() {
        when(normalizer.normalize(app, envelope, NOW)).thenReturn(Optional.of(
            new NormalizedMeetingEvent("minutes.meeting_record_created", "evt-1", "meeting-1", "minute-1", NOW)));
        when(enterprises.lockEnterprise("ent-1")).thenReturn(Optional.of("ent-1"));
        when(store.waitingForMeeting("ent-1", "app-1", "meeting-1")).thenReturn(null);
        when(subscriptions.selectCount(any())).thenReturn(1L);
        when(events.selectOne(any())).thenReturn(null);

        assertTrue(service.accept("ent-1", app, envelope));

        verify(jobs).enqueue(anyString(), eq("ent-1"), isNull(), eq("meeting_event"), anyString(), anyString(), eq(NOW));
        verify(jobs).setAttemptLimit(anyString(), eq(MeetingEventTransactions.ARTIFACT_WAIT_MAX_ATTEMPTS));
        verify(events).insert(any(MeetingEventRow.class));
    }

    private MeetingEventRow waitingRow() {
        var row = new MeetingEventRow();
        row.setId("evt-1");
        row.setEnterpriseId("ent-1");
        row.setIntegrationId("app-1");
        row.setMeetingId("meeting-1");
        row.setEventType("meeting.ended");
        row.setStatus("waiting");
        row.setJobId("job-1");
        return row;
    }
}
