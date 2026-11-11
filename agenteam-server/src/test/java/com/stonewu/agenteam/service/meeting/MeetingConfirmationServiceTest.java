package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.meeting.entity.MeetingConfirmationRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 确认闭环回答的编排：身份、版本与状态校验，以及确认字段以人工写策略交给可信写入。 */
class MeetingConfirmationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final MeetingConfirmationStore store = mock(MeetingConfirmationStore.class);
    private final MeetingConfirmationCommandService commands = mock(MeetingConfirmationCommandService.class);
    private final EnterpriseMapper enterprises = mock(EnterpriseMapper.class);
    private final com.stonewu.agenteam.mapper.meeting.MeetingSubscriptionMapper subscriptions =
        mock(com.stonewu.agenteam.mapper.meeting.MeetingSubscriptionMapper.class);
    private final com.stonewu.agenteam.mapper.execution.RunMapper runs =
        mock(com.stonewu.agenteam.mapper.execution.RunMapper.class);
    private final MeetingEventAccessService access = mock(MeetingEventAccessService.class);
    private final MeetingEventPolicy eventPolicy = mock(MeetingEventPolicy.class);
    private final com.stonewu.agenteam.service.execution.RunSubmissionService submission =
        mock(com.stonewu.agenteam.service.execution.RunSubmissionService.class);
    private final MeetingConfirmationService service =
        new MeetingConfirmationService(store, commands, enterprises, subscriptions, runs, access, eventPolicy,
            submission, CLOCK);

    @Test
    void rejectsNonOwner() {
        when(enterprises.lockEnterprise("ent")).thenReturn(Optional.of("ent"));
        when(store.find("ent", "binding", "task-1")).thenReturn(pendingRow("owner-A", 1L, "msg-1", null));

        var failure = assertThrows(ApiException.class, () -> service.answer("ent", "binding", "task-1", 1L, "msg-1",
            MeetingConfirmationAction.CONFIRM, "同意", actor("owner-B"), "evt-1"));
        assertEquals("MEETING_CONFIRM_OWNER_REQUIRED", failure.code());
        assertEquals(HttpStatus.FORBIDDEN, failure.getStatusCode());
        verify(store, never()).answer(anyString(), anyString(), anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void rejectsStaleVersion() {
        when(enterprises.lockEnterprise("ent")).thenReturn(Optional.of("ent"));
        when(store.find("ent", "binding", "task-1")).thenReturn(pendingRow("owner-A", 2L, "msg-1", null));

        var failure = assertThrows(ApiException.class, () -> service.answer("ent", "binding", "task-1", 1L, "msg-1",
            MeetingConfirmationAction.CONFIRM, null, actor("owner-A"), "evt-1"));
        assertEquals("MEETING_CONFIRM_STALE", failure.code());
        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
    }

    @Test
    void rejectsMismatchedCardMessage() {
        when(enterprises.lockEnterprise("ent")).thenReturn(Optional.of("ent"));
        when(store.find("ent", "binding", "task-1")).thenReturn(pendingRow("owner-A", 1L, "msg-1", null));

        var failure = assertThrows(ApiException.class, () -> service.answer("ent", "binding", "task-1", 1L, "msg-other",
            MeetingConfirmationAction.CONFIRM, null, actor("owner-A"), "evt-1"));
        assertEquals("MEETING_CONFIRM_STALE", failure.code());
    }

    @Test
    void returnsHandledToastForDuplicateEvent() {
        when(enterprises.lockEnterprise("ent")).thenReturn(Optional.of("ent"));
        when(store.find("ent", "binding", "task-1")).thenReturn(pendingRow("owner-A", 1L, "msg-1", "evt-1"));

        var result = service.answer("ent", "binding", "task-1", 1L, "msg-1",
            MeetingConfirmationAction.CONFIRM, null, actor("owner-A"), "evt-1");
        assertEquals("info", result.at("/toast/type").asText());
        assertEquals("此操作已处理。", result.at("/toast/content").asText());
        verify(store, never()).answer(anyString(), anyString(), anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void confirmsAsOwnerAndPublishesMappedWrite() {
        when(enterprises.lockEnterprise("ent")).thenReturn(Optional.of("ent"));
        when(store.find("ent", "binding", "task-1")).thenReturn(pendingRow("owner-A", 1L, "msg-1", null));
        when(store.answer(eq("ent"), eq("binding"), eq("task-1"), eq(1L), eq(MeetingConfirmationAction.CONFIRM),
            eq("同意"), eq("evt-1"), any(Instant.class))).thenReturn(true);

        var result = service.answer("ent", "binding", "task-1", 1L, "msg-1",
            MeetingConfirmationAction.CONFIRM, "同意", actor("owner-A"), "evt-1");

        assertEquals("success", result.at("/toast/type").asText());
        assertEquals("已确认接受。", result.at("/toast/content").asText());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(commands).publish(eq("ent"), eq("binding"), eq("task-1"), eq("confirm:evt-1"), fields.capture());
        var captured = fields.getValue();
        assertEquals("进行中", captured.get("task_status"));
    }

    private MeetingConfirmationRow pendingRow(String ownerUserId, long taskVersion, String cardMessageId, String lastEventId) {
        var row = new MeetingConfirmationRow();
        row.setOwnerUserId(ownerUserId);
        row.setTaskVersion(taskVersion);
        row.setStatus(MeetingConfirmationState.PENDING.value());
        row.setCardMessageId(cardMessageId);
        row.setLastEventId(lastEventId);
        return row;
    }

    private AuthContext actor(String userId) {
        var user = new UserEntity(userId, "user-" + userId, "pwd", "显示名", "active", false,
            null, null, null, 0L, null, 0L, null, null);
        return new AuthContext(user, "ent", Set.of());
    }
}
