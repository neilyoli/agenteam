package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.execution.response.RunAccepted;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.execution.ExecutionConfigurationService;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 资料就绪等待：会议结束事件未携带 minute_token 时落 waiting 退避等待，超时终止，就绪后创建 Run。 */
class MeetingEventTransactionsTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final BackgroundJobMapper jobs = mock(BackgroundJobMapper.class);
    private final EnterpriseMapper enterprises = mock(EnterpriseMapper.class);
    private final MeetingEventStore store = mock(MeetingEventStore.class);
    private final MeetingEventAccessService access = mock(MeetingEventAccessService.class);
    private final MeetingEventPolicy policy = mock(MeetingEventPolicy.class);
    private final RunSubmissionService submission = mock(RunSubmissionService.class);
    private final ResourceJson json = new ResourceJson(new ObjectMapper());

    private final MeetingEventTransactions transactions =
        new MeetingEventTransactions(jobs, enterprises, store, access, policy, submission, json, CLOCK);

    @Test
    void waitsWhenMeetingEndedWithoutMinuteToken() {
        var lease = lease(1);
        var row = row("meeting.ended", null);
        stubReadyRoute(row);

        transactions.submit(lease);

        verify(store).markWaiting(eq(row), eq(NOW.plusSeconds(60)));
        verify(store).finish(eq(lease), eq("queued"), eq("MEETING_ARTIFACT_PENDING"), anyString(),
            eq(NOW.plusSeconds(60)));
        verify(submission, never()).meetingEvent(any(), any(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyInt(), any());
        verify(store, never()).markRouted(any(), anyString(), anyString());
    }

    @Test
    void failsWhenArtifactWaitExhausted() {
        var lease = lease(MeetingEventTransactions.ARTIFACT_WAIT_MAX_ATTEMPTS);
        var row = row("meeting.ended", null);
        stubReadyRoute(row);

        transactions.submit(lease);

        verify(store).markFailed(eq(row), eq("MEETING_ARTIFACT_TIMEOUT"));
        verify(store).finish(eq(lease), eq("failed"), eq("MEETING_ARTIFACT_TIMEOUT"), anyString(), any());
        verify(store, never()).markWaiting(any(), any());
    }

    @Test
    void createsRunWhenMinuteTokenPresent() {
        var lease = lease(1);
        var row = row("meeting.ended", "minute-1");
        stubReadyRoute(row);
        when(submission.meetingEvent(any(), any(), any(), any(), any(), any(), any(),
            anyInt(), any())).thenReturn(Optional.of(new RunAccepted("conv-1", "run-1", "in-1", "out-1", "running",
            "seq-1")));

        transactions.submit(lease);

        verify(store).markRouted(eq(row), eq("run-1"), eq("conv-1"));
        verify(store).finish(eq(lease), eq("completed"), isNull(), isNull(), any());
        verify(store, never()).markWaiting(any(), any());
    }

    @Test
    void nonEndedEventWithoutMinuteTokenStillCreatesRun() {
        var lease = lease(1);
        var row = row("meeting.advanced_meeting_started", null);
        stubReadyRoute(row);
        when(submission.meetingEvent(any(), any(), any(), any(), any(), any(), any(),
            anyInt(), any())).thenReturn(Optional.of(new RunAccepted("conv-1", "run-1", "in-1", "out-1", "running",
            "seq-1")));

        transactions.submit(lease);

        verify(store).markRouted(eq(row), eq("run-1"), eq("conv-1"));
        verify(store, never()).markWaiting(any(), any());
    }

    @Test
    void artifactWaitSecondsBoundedBackoff() {
        assertEquals(60, MeetingEventTransactions.artifactWaitSeconds(0));
        assertEquals(60, MeetingEventTransactions.artifactWaitSeconds(1));
        assertEquals(120, MeetingEventTransactions.artifactWaitSeconds(2));
        assertEquals(300, MeetingEventTransactions.artifactWaitSeconds(5));
        assertEquals(300, MeetingEventTransactions.artifactWaitSeconds(100));
    }

    private void stubReadyRoute(MeetingEventRow row) {
        when(enterprises.lockEnterprise("ent")).thenReturn(Optional.of("ent"));
        when(store.find("ent", "evt-1")).thenReturn(row);
        when(policy.findEnabled("ent", "integ-1", row.getEventType())).thenReturn(subscription(row.getEventType()));
        when(policy.pluginActive("ent", "1.0.0")).thenReturn(true);
        when(access.requireOwner(any())).thenReturn(actor());
        when(policy.select(any(), anyString(), anyString())).thenReturn(selection());
    }

    private JobLease lease(int attemptCount) {
        return new JobLease("job-1", "ent", null, "meeting_event",
            json.write(json.tree(Map.of("meetingEventId", "evt-1"))), attemptCount,
            MeetingEventTransactions.ARTIFACT_WAIT_MAX_ATTEMPTS, "worker-1", 1L, false);
    }

    private MeetingEventRow row(String eventType, String minuteToken) {
        var row = new MeetingEventRow();
        row.setId("evt-1");
        row.setEnterpriseId("ent");
        row.setJobId("job-1");
        row.setStatus("received");
        row.setIntegrationId("integ-1");
        row.setEventType(eventType);
        row.setMeetingId("meeting-1");
        row.setMinuteToken(minuteToken);
        return row;
    }

    private MeetingSubscriptionRow subscription(String eventType) {
        var subscription = new MeetingSubscriptionRow();
        subscription.setId("sub-1");
        subscription.setEnterpriseId("ent");
        subscription.setIntegrationId("integ-1");
        subscription.setEventType(eventType);
        subscription.setStatus("enabled");
        subscription.setHireId("hire-1");
        subscription.setAgentVersionId("version-1");
        subscription.setPluginVersion("1.0.0");
        subscription.setOwnerUserId("user-1");
        return subscription;
    }

    private AuthContext actor() {
        return new AuthContext(user(), "ent", Set.of("agent.run"));
    }

    private UserEntity user() {
        return new UserEntity("user-1", "owner", null, "负责人", "active", false, "ent", "owner@example.com", null, 0L,
            null, 1L, NOW, NOW);
    }

    private ExecutionConfigurationService.Selection selection() {
        return new ExecutionConfigurationService.Selection("agent-1", "version-1", "hire-1", "名称",
            (ObjectNode) json.tree(Map.of("config", Map.of())));
    }
}
