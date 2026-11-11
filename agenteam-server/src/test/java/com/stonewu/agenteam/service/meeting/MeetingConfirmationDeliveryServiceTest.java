package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingConfirmationMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingTaskIdentityMapper;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingConfirmationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskIdentityRow;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 确认卡片持久化投递：delivered 后才建轮次、稳定命令编号、租约投递与失败分类。 */
class MeetingConfirmationDeliveryServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final MeetingConfirmationMapper confirmations = mock(MeetingConfirmationMapper.class);
    private final MeetingTaskIdentityMapper identities = mock(MeetingTaskIdentityMapper.class);
    private final MeetingBindingMapper bindings = mock(MeetingBindingMapper.class);
    private final MeetingConfirmationCardSender cards = mock(MeetingConfirmationCardSender.class);
    private final ChatIdentityService channelIdentity = mock(ChatIdentityService.class);
    private final MeetingConfirmationDeliveryService service =
        new MeetingConfirmationDeliveryService(confirmations, identities, bindings, cards, channelIdentity, CLOCK);

    @Test
    void createsPendingRoundOnlyAfterDeliveredWithResolvedOwner() {
        when(confirmations.deliveredTasksAwaitingConfirmation(anyInt())).thenReturn(List.of(outbox(3)));
        when(confirmations.find("ent", "binding-1", "T-task-1")).thenReturn(null);
        when(identities.find("ent", "T-task-1")).thenReturn(identity());
        when(bindings.selectById("binding-1")).thenReturn(binding());
        when(channelIdentity.find("ent", "integ-1", "feishu_open_id", "ou_owner"))
            .thenReturn(Optional.of(ownerBinding()));
        when(confirmations.dueCardSends(any(), anyInt())).thenReturn(List.of());
        when(confirmations.selectList(any())).thenReturn(List.of());

        assertTrue(service.runOnce());

        ArgumentCaptor<MeetingConfirmationRow> captor = ArgumentCaptor.forClass(MeetingConfirmationRow.class);
        verify(confirmations).insert(captor.capture());
        var row = captor.getValue();
        assertEquals(3L, row.getTaskVersion());
        assertEquals(MeetingConfirmationState.PENDING.value(), row.getStatus());
        assertEquals("pending", row.getCardStatus());
        assertEquals("card:binding-1:T-task-1:3", row.getCardCommandId());
        assertEquals("user-owner", row.getOwnerUserId());
        assertEquals("ou_owner", row.getOwnerSubject());
    }

    @Test
    void marksUnresolvedWhenOwnerNotBoundAndNeverSendsCard() {
        when(confirmations.deliveredTasksAwaitingConfirmation(anyInt())).thenReturn(List.of(outbox(1)));
        when(confirmations.find("ent", "binding-1", "T-task-1")).thenReturn(null);
        when(identities.find("ent", "T-task-1")).thenReturn(identity());
        when(bindings.selectById("binding-1")).thenReturn(binding());
        when(channelIdentity.find("ent", "integ-1", "feishu_open_id", "ou_owner")).thenReturn(Optional.empty());
        when(confirmations.dueCardSends(any(), anyInt())).thenReturn(List.of());
        when(confirmations.selectList(any())).thenReturn(List.of());

        service.runOnce();

        ArgumentCaptor<MeetingConfirmationRow> captor = ArgumentCaptor.forClass(MeetingConfirmationRow.class);
        verify(confirmations).insert(captor.capture());
        assertEquals(MeetingConfirmationState.UNRESOLVED.value(), captor.getValue().getStatus());
        assertEquals("unresolved", captor.getValue().getCardStatus());
        verify(cards, never()).send(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void advancesExistingRoundWhenNewVersionDelivered() {
        var delivered = outbox(5);
        when(confirmations.deliveredTasksAwaitingConfirmation(anyInt())).thenReturn(List.of(delivered));
        var existing = pendingRound(3L);
        when(confirmations.find("ent", "binding-1", "T-task-1")).thenReturn(existing);
        when(identities.find("ent", "T-task-1")).thenReturn(identity());
        when(bindings.selectById("binding-1")).thenReturn(binding());
        when(channelIdentity.find("ent", "integ-1", "feishu_open_id", "ou_owner"))
            .thenReturn(Optional.of(ownerBinding()));
        when(confirmations.dueCardSends(any(), anyInt())).thenReturn(List.of());
        when(confirmations.selectList(any())).thenReturn(List.of());

        service.runOnce();

        verify(confirmations).beginRound(eq("ent"), eq("binding-1"), eq("T-task-1"), eq(5L),
            eq("user-owner"), eq("ou_owner"), eq(MeetingConfirmationState.PENDING.value()), eq("pending"),
            anyString(), anyString(), eq("card:binding-1:T-task-1:5"), any());
        verify(confirmations, never()).insert(any(MeetingConfirmationRow.class));
    }

    @Test
    void skipsWhenIdentityMissingOrRoundAlreadyCurrent() {
        when(confirmations.deliveredTasksAwaitingConfirmation(anyInt())).thenReturn(List.of(outbox(3)));
        when(confirmations.find("ent", "binding-1", "T-task-1")).thenReturn(null);
        when(identities.find("ent", "T-task-1")).thenReturn(null);
        when(confirmations.dueCardSends(any(), anyInt())).thenReturn(List.of());
        when(confirmations.selectList(any())).thenReturn(List.of());

        assertTrue(service.runOnce());
        verify(confirmations, never()).insert(any(MeetingConfirmationRow.class));

        // 确认轮次版本不低于已投递版本时不重复创建有效轮次。
        when(confirmations.deliveredTasksAwaitingConfirmation(anyInt())).thenReturn(List.of(outbox(2)));
        when(confirmations.find("ent", "binding-1", "T-task-1")).thenReturn(pendingRound(3L));
        service.runOnce();
        verify(confirmations, never()).beginRound(anyString(), anyString(), anyString(), anyLong(), any(), any(),
            any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendsClaimedCardWithStableCommandIdAndCompletes() {
        var row = pendingRound(2L);
        row.setId("conf-1");
        when(confirmations.deliveredTasksAwaitingConfirmation(anyInt())).thenReturn(List.of());
        when(confirmations.dueCardSends(any(), anyInt())).thenReturn(List.of(row));
        when(confirmations.claimCardSend(eq("ent"), eq("conf-1"), anyString(), any(), any())).thenReturn(1);
        row.setCardStatus("sending");
        when(confirmations.selectById("conf-1")).thenReturn(row);
        when(bindings.selectById("binding-1")).thenReturn(binding());
        when(cards.send(eq("ent"), eq("integ-1"), eq("ou_owner"), any(JsonNode.class), eq("card:binding-1:T-task-1:2")))
            .thenReturn("om-123");

        service.runOnce();

        verify(confirmations).completeCardSend(eq("ent"), eq("conf-1"), anyString(), eq("om-123"), any());
    }

    @Test
    void classifiesRejectionsAndUnknownOutcomes() {
        // 结果不明：停止自动重发，转人工核查。
        runCardFailure(new MeetingCardDeliveryException(MeetingCardDeliveryException.Outcome.UNKNOWN, false,
            "确认卡片投递未取得明确结果。", null));
        verify(confirmations).unknownCardSend(eq("ent"), eq("conf-1"), anyString(), anyString(), any());
        verify(confirmations, never()).failCardSend(anyString(), anyString(), anyString(), any(), anyString(),
            anyString(), any());

        // 明确拒绝且不可重试（参数/权限错误）：阻断。
        runCardFailure(new MeetingCardDeliveryException(MeetingCardDeliveryException.Outcome.REJECTED, false,
            "确认卡片投递被飞书拒绝（99991661）。", null));
        verify(confirmations).failCardSend(eq("ent"), eq("conf-1"), anyString(), any(), eq("blocked"),
            anyString(), any());

        // 明确拒绝且可重试（限流）：退避重试。
        runCardFailure(new MeetingCardDeliveryException(MeetingCardDeliveryException.Outcome.REJECTED, true,
            "确认卡片投递被飞书拒绝（99991400）。", null));
        verify(confirmations).failCardSend(eq("ent"), eq("conf-1"), anyString(), any(), eq("failed"),
            anyString(), any());
    }

    private void runCardFailure(MeetingCardDeliveryException failure) {
        reset(confirmations, cards);
        var row = pendingRound(2L);
        row.setId("conf-1");
        when(confirmations.deliveredTasksAwaitingConfirmation(anyInt())).thenReturn(List.of());
        when(confirmations.dueCardSends(any(), anyInt())).thenReturn(List.of(row));
        when(confirmations.claimCardSend(eq("ent"), eq("conf-1"), anyString(), any(), any())).thenReturn(1);
        row.setCardStatus("sending");
        when(confirmations.selectById("conf-1")).thenReturn(row);
        when(bindings.selectById("binding-1")).thenReturn(binding());
        when(cards.send(anyString(), anyString(), anyString(), any(), anyString())).thenThrow(failure);
        service.runOnce();
    }

    private MeetingSyncOutboxRow outbox(long deliveredVersion) {
        var row = new MeetingSyncOutboxRow();
        row.setEnterpriseId("ent");
        row.setBindingId("binding-1");
        row.setBusinessKey("T-task-1");
        row.setDeliveredVersion(deliveredVersion);
        row.setRunId("run-1");
        return row;
    }

    private MeetingTaskIdentityRow identity() {
        var row = new MeetingTaskIdentityRow();
        row.setId("T-task-1");
        row.setEnterpriseId("ent");
        row.setMeetingId("mtg-1");
        row.setTitleCurrent("落地对接方案");
        row.setOwnerOpenId("ou_owner");
        row.setOwnerName("张三");
        row.setSourceCount(1);
        row.setStatus("active");
        return row;
    }

    private MeetingBindingRow binding() {
        var row = new MeetingBindingRow();
        row.setId("binding-1");
        row.setEnterpriseId("ent");
        row.setIntegrationId("integ-1");
        row.setBusinessType("task");
        row.setStatus("enabled");
        return row;
    }

    private UserChannelBindingRow ownerBinding() {
        var row = new UserChannelBindingRow();
        row.setUserId("user-owner");
        return row;
    }

    private MeetingConfirmationRow pendingRound(long version) {
        var row = new MeetingConfirmationRow();
        row.setId("conf-" + version);
        row.setEnterpriseId("ent");
        row.setBindingId("binding-1");
        row.setBusinessKey("T-task-1");
        row.setTaskVersion(version);
        row.setOwnerUserId("user-owner");
        row.setOwnerSubject("ou_owner");
        row.setStatus(MeetingConfirmationState.PENDING.value());
        row.setCardCommandId("card:binding-1:T-task-1:" + version);
        row.setCardStatus("pending");
        row.setCardTitle("会议任务确认：落地对接方案");
        row.setCardDescription("请确认。");
        row.setCardAttemptCount(0);
        row.setCardMaxAttempts(6);
        row.setCardNextAttemptAt(NOW);
        return row;
    }
}
