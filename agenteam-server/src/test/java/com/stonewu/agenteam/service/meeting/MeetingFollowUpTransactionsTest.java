package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpScanRow;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import com.stonewu.agenteam.service.meeting.MeetingBitableClient.MeetingRecord;
import com.stonewu.agenteam.service.meeting.MeetingBitableClient.MeetingRecordPage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 督办扫描编排：限频栅栏不重复轰炸、催办成功后回填、非工作时段跳过、投递失败单独计数。 */
class MeetingFollowUpTransactionsTest {
    private static final String BINDING_ID = "binding-1";
    private static final String INTEGRATION_ID = "integration-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock IN_WINDOW = Clock.fixed(Instant.parse("2026-01-01T02:00:00Z"), ZoneOffset.UTC); // 10:00 上海
    private static final Clock OUTSIDE = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC); // 08:00 上海

    private final MeetingFollowUpStore store = mock(MeetingFollowUpStore.class);
    private final MeetingBindingMapper bindings = mock(MeetingBindingMapper.class);
    private final IntegrationQueryService integrations = mock(IntegrationQueryService.class);
    private final IntegrationTokenService tokens = mock(IntegrationTokenService.class);
    private final MeetingBitableClient bitable = mock(MeetingBitableClient.class);
    private final MeetingFollowUpClassifier classifier = mock(MeetingFollowUpClassifier.class);
    private final MeetingConfirmationCardSender cards = mock(MeetingConfirmationCardSender.class);

    @Test
    void rateLimitsByStoreFenceWithoutResending() {
        stubDelivery(IN_WINDOW);
        when(store.remind(eq("ent"), eq(BINDING_ID), eq("task-1"), any(Instant.class), any(Instant.class), anyString()))
            .thenReturn(false);

        assertTrue(transactions(IN_WINDOW).runOnce());

        verify(cards, never()).send(anyString(), anyString(), anyString(), any(), anyString());
        verify(store, never()).recordSent(anyString(), anyString(), anyString(), any(Instant.class));
        var result = completedResult();
        assertTrue(result.contains("\"reminded\":0"));
        assertTrue(result.contains("\"skipped\":1"));
    }

    @Test
    void sendsReminderAndRecordsSentWhenRemindWins() {
        stubDelivery(IN_WINDOW);
        when(store.remind(eq("ent"), eq(BINDING_ID), eq("task-1"), any(Instant.class), any(Instant.class), anyString()))
            .thenReturn(true);
        when(cards.send(eq("ent"), eq(INTEGRATION_ID), eq("ou_owner"), any(JsonNode.class), anyString())).thenReturn("msg-1");

        assertTrue(transactions(IN_WINDOW).runOnce());

        verify(cards).send(eq("ent"), eq(INTEGRATION_ID), eq("ou_owner"), any(JsonNode.class), anyString());
        verify(store).recordSent("ent", BINDING_ID, "task-1", IN_WINDOW.instant());
        assertTrue(completedResult().contains("\"reminded\":1"));
    }

    @Test
    void skipsOutsideWorkingWindowWithoutScanning() {
        var scan = scanRow(OUTSIDE);
        when(bindings.selectOne(any())).thenReturn(binding());
        stubToken();
        when(store.due(OUTSIDE.instant(), 20)).thenReturn(List.of(scan));
        when(store.claim("ent", "scan-1", OUTSIDE.instant()))
            .thenReturn(Optional.of(new MeetingFollowUpStore.Attempt(scan, "tok")));

        assertTrue(transactions(OUTSIDE).runOnce());

        verify(bitable, never()).listRecords(any(), any(), any(), any(), any(), anyInt());
        verify(cards, never()).send(anyString(), anyString(), anyString(), any(), anyString());
        assertTrue(completedResult().contains("outside_window"));
    }

    @Test
    void countsSendFailureWithoutRecordingSent() {
        stubDelivery(IN_WINDOW);
        when(store.remind(eq("ent"), eq(BINDING_ID), eq("task-1"), any(Instant.class), any(Instant.class), anyString()))
            .thenReturn(true);
        when(cards.send(anyString(), anyString(), anyString(), any(), anyString()))
            .thenThrow(new ChannelProviderException("MEETING_CARD_SEND_FAILED", "x"));

        assertTrue(transactions(IN_WINDOW).runOnce());

        verify(store, never()).recordSent(anyString(), anyString(), anyString(), any(Instant.class));
        assertTrue(completedResult().contains("\"failed\":1"));
    }

    private MeetingFollowUpTransactions transactions(Clock clock) {
        return new MeetingFollowUpTransactions(store, bindings, integrations, tokens, bitable, classifier, cards, JSON, clock);
    }

    private void stubDelivery(Clock clock) {
        var scan = scanRow(clock);
        when(bindings.selectOne(any())).thenReturn(binding());
        stubToken();
        when(bitable.listRecords(eq(INTEGRATION_ID), eq("token"), eq("app-1"), eq("tbl-1"), isNull(), eq(500)))
            .thenReturn(new MeetingRecordPage(List.of(new MeetingRecord("rec-1", JsonNodeFactory.instance.objectNode())),
                false, null));
        when(classifier.classify(any(), any(), any(), any(), any())).thenReturn(Optional.of(candidate()));
        when(store.find("ent", BINDING_ID, "task-1")).thenReturn(null);
        when(store.due(clock.instant(), 20)).thenReturn(List.of(scan));
        when(store.claim("ent", "scan-1", clock.instant()))
            .thenReturn(Optional.of(new MeetingFollowUpStore.Attempt(scan, "tok")));
    }

    private void stubToken() {
        var integrationRow = mock(EnterpriseIntegrationRow.class);
        var application = mock(IntegrationApplication.class);
        when(integrations.require("ent", INTEGRATION_ID)).thenReturn(integrationRow);
        when(integrations.application(integrationRow)).thenReturn(application);
        when(tokens.get(application)).thenReturn(new ChannelAccessToken("token", 3600));
    }

    private MeetingBindingRow binding() {
        var row = new MeetingBindingRow();
        row.setId(BINDING_ID);
        row.setEnterpriseId("ent");
        row.setIntegrationId(INTEGRATION_ID);
        row.setAppToken("app-1");
        row.setTableId("tbl-1");
        row.setBusinessType("task");
        row.setKeyField("任务唯一键");
        row.setFieldMappingJson("{\"title\":\"标题\",\"owner\":\"负责人\",\"confirm_status\":\"确认状态\"}");
        row.setStatus("enabled");
        return row;
    }

    private MeetingFollowUpScanRow scanRow(Clock clock) {
        var row = new MeetingFollowUpScanRow();
        row.setId("scan-1");
        row.setEnterpriseId("ent");
        row.setBindingId(BINDING_ID);
        row.setScheduleId("sched-1");
        row.setOccurrenceId("occ-1");
        row.setPolicyJson(policyJson());
        row.setStatus("queued");
        row.setAttemptCount(0);
        row.setMaxAttempts(8);
        row.setNextAttemptAt(clock.instant());
        return row;
    }

    private String policyJson() {
        try {
            return JSON.writeValueAsString(new MeetingFollowUpPolicy(BINDING_ID, 1440, 9, 18, Set.of("unconfirmed")));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private MeetingFollowUpCandidate candidate() {
        ObjectNode evidence = JsonNodeFactory.instance.objectNode();
        evidence.put("task_key", "task-1");
        evidence.put("confirm_status", "待确认");
        return new MeetingFollowUpCandidate("task-1", "标题", "ou_owner", List.of("unconfirmed"), evidence);
    }

    private String completedResult() {
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(store).complete(any(MeetingFollowUpStore.Attempt.class), result.capture(), any(Instant.class));
        return result.getValue();
    }
}
