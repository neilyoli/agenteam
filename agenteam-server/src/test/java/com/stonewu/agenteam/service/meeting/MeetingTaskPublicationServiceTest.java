package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import com.stonewu.agenteam.model.meeting.request.MeetingSyncCommand;
import com.stonewu.agenteam.model.meeting.response.MeetingSyncCommandResult;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 会议任务发布的宿主二次校验：身份、参会人、证据、截止日期与字段构造全部重新验证。 */
class MeetingTaskPublicationServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ResourceJson RESOURCE_JSON = new ResourceJson(JSON);

    private final MeetingBindingMapper bindings = mock(MeetingBindingMapper.class);
    private final MeetingSyncCommandService commands = mock(MeetingSyncCommandService.class);
    private final MeetingRunExecutionPolicy runPolicy = mock(MeetingRunExecutionPolicy.class);
    private final MeetingTaskIdentityService taskKeys = mock(MeetingTaskIdentityService.class);
    private final ChatIdentityService channelIdentity = mock(ChatIdentityService.class);
    private final MeetingTaskPublicationService service =
        new MeetingTaskPublicationService(bindings, commands, runPolicy, taskKeys, channelIdentity, RESOURCE_JSON);

    @Test
    void skipsL3WithoutEnqueue() {
        prepareAuthorization();
        var result = service.publish(context(run("meeting_event")), args("L3", validExtras()));

        assertEquals("skipped", result.path("status").asText());
        assertFalse(result.path("isError").asBoolean());
        verify(commands, never()).publish(anyString(), any());
    }

    @Test
    void queuesValidTaskForMeetingEventRun() {
        prepareAuthorization();
        when(taskKeys.resolve(eq("ent"), eq("task-binding"), any())).thenReturn("T-stable-key");
        when(commands.bindingMapping(any())).thenReturn(fullMapping());
        when(commands.publish(eq("ent"), any())).thenReturn(
            new MeetingSyncCommandResult("task-binding", "T-stable-key", "op-1"));

        var result = service.publish(context(run("meeting_event")), args("L1", validExtras()));

        assertEquals("queued", result.path("status").asText());
        assertEquals("T-stable-key", result.path("businessKey").asText());
        assertEquals("op-1", result.path("commandId").asText());
        assertTrue(result.path("content").asText().contains("尚未确认飞书写入完成"));

        var command = capturedCommand();
        var fields = command.fields();
        assertEquals("T-stable-key", fields.get("task_key"));
        assertEquals("mtg-1", fields.get("meeting_id"));
        assertEquals(java.util.List.of("ou_owner"), fields.get("owner"));
        assertEquals("2031-01-15 00:00:00", fields.get("due_date"));
        assertEquals(Boolean.TRUE, fields.get("l1"));
        assertEquals("落地对接方案", fields.get("title"));
        // 人工字段绝不进入投递 payload。
        assertFalse(fields.containsKey("task_status"));
    }

    @Test
    void dropsUnmappedOptionalFieldsButRequiresUniqueKeyAndTitle() {
        prepareAuthorization();
        when(taskKeys.resolve(eq("ent"), eq("task-binding"), any())).thenReturn("T-stable-key");
        // 只有唯一键映射，标题未映射：必须拒绝并提示修复绑定。
        when(commands.bindingMapping(any())).thenReturn(Map.of("task_key", "任务ID"));

        var failure = assertThrows(ApiException.class,
            () -> service.publish(context(run("meeting_event")), args("L1", validExtras())));
        assertEquals("MEETING_FIELD_NOT_MAPPED", failure.code());

        // 唯一键与标题已映射、其余未映射：可发布，未映射字段不进入 payload。
        when(commands.bindingMapping(any())).thenReturn(Map.of("task_key", "任务ID", "title", "待办"));
        when(commands.publish(eq("ent"), any())).thenReturn(
            new MeetingSyncCommandResult("task-binding", "T-stable-key", "op-1"));
        var result = service.publish(context(run("meeting_event")), args("L1", validExtras()));
        assertEquals("queued", result.path("status").asText());
        var fields = capturedCommand().fields();
        assertEquals(2, fields.size());
    }

    @Test
    void rejectsMeetingIdMismatchForAutoRun() {
        prepareAuthorization();
        var extras = validExtras();
        extras.put("meetingId", "mtg-other");
        var failure = assertThrows(ApiException.class,
            () -> service.publish(context(run("meeting_event")), args("L1", extras)));
        assertEquals("VALIDATION_FAILED", failure.code());
        verify(commands, never()).publish(anyString(), any());
    }

    @Test
    void rejectsOwnerOutsideParticipantsForAutoRun() {
        prepareAuthorization();
        var extras = validExtras();
        extras.put("participantOpenIds", JSON.valueToTree(java.util.List.of("ou_other")));
        var failure = assertThrows(ApiException.class,
            () -> service.publish(context(run("meeting_event")), args("L1", extras)));
        assertEquals("MEETING_OWNER_NOT_PARTICIPANT", failure.code());
    }

    @Test
    void rejectsMissingParticipantsForAutoRun() {
        prepareAuthorization();
        var extras = validExtras();
        extras.remove("participantOpenIds");
        var failure = assertThrows(ApiException.class,
            () -> service.publish(context(run("meeting_event")), args("L1", extras)));
        assertEquals("MEETING_OWNER_NOT_PARTICIPANT", failure.code());
    }

    @Test
    void rejectsUnboundOwner() {
        prepareAuthorization();
        when(channelIdentity.find("ent", "integ-1", "feishu_open_id", "ou_owner")).thenReturn(Optional.empty());
        var failure = assertThrows(ApiException.class,
            () -> service.publish(context(run("meeting_event")), args("L1", validExtras())));
        assertEquals("MEETING_OWNER_UNBOUND", failure.code());
    }

    @Test
    void rejectsMissingEvidenceAnchorOrDueDate() {
        prepareAuthorization();
        var withoutAnchor = args("L1", validExtras());
        ((ObjectNode) withoutAnchor.path("evidence").get(0)).remove("anchor");
        var failure = assertThrows(ApiException.class,
            () -> service.publish(context(run("meeting_event")), withoutAnchor));
        assertEquals("VALIDATION_FAILED", failure.code());

        var noDue = args("L1", validExtras());
        ((ObjectNode) noDue).remove("dueDate");
        assertThrows(ApiException.class, () -> service.publish(context(run("meeting_event")), noDue));
    }

    @Test
    void supportsInteractiveRunWithUserApprovalPath() {
        when(bindings.selectOne(any())).thenReturn(taskBinding());
        when(channelIdentity.find("ent", "integ-1", "feishu_open_id", "ou_owner")).thenReturn(Optional.of(owner()));
        when(taskKeys.resolve(eq("ent"), eq("task-binding"), any())).thenReturn("T-stable-key");
        when(commands.bindingMapping(any())).thenReturn(fullMapping());
        when(commands.publish(eq("ent"), any())).thenReturn(
            new MeetingSyncCommandResult("task-binding", "T-stable-key", "op-1"));

        var result = service.publish(context(run("interactive")), args("L2", validExtras()));
        assertEquals("queued", result.path("status").asText());
        // 交互路径不做参会人强校验（写入前已有用户审批），但负责人绑定校验仍执行。
        verify(channelIdentity).find("ent", "integ-1", "feishu_open_id", "ou_owner");
    }

    @Test
    void rejectsUnsupportedModes() {
        var failure = assertThrows(ApiException.class,
            () -> service.publish(context(run("scheduled")), args("L1", validExtras())));
        assertEquals("MEETING_PUBLISH_MODE_UNSUPPORTED", failure.code());
        verify(runPolicy, never()).requireAutoPublish(any());
    }

    @Test
    void rejectsWhenRunAuthorizationFails() {
        when(runPolicy.requireAutoPublish(any())).thenThrow(new ApiException(
            org.springframework.http.HttpStatus.FORBIDDEN, "MEETING_RUN_AUTHORIZATION_INVALID", "订阅已停用。"));
        var failure = assertThrows(ApiException.class,
            () -> service.publish(context(run("meeting_event")), args("L1", validExtras())));
        assertEquals("MEETING_RUN_AUTHORIZATION_INVALID", failure.code());
        verify(commands, never()).publish(anyString(), any());
    }

    private MeetingSyncCommand capturedCommand() {
        ArgumentCaptor<MeetingSyncCommand> captor = ArgumentCaptor.forClass(MeetingSyncCommand.class);
        verify(commands, atLeastOnce()).publish(eq("ent"), captor.capture());
        return captor.getValue();
    }

    private void prepareAuthorization() {
        var binding = taskBinding();
        when(runPolicy.requireAutoPublish(any())).thenReturn(
            new MeetingRunExecutionPolicy.MeetingRunAuthorization(event(), subscription(), binding));
        when(channelIdentity.find("ent", "integ-1", "feishu_open_id", "ou_owner")).thenReturn(Optional.of(owner()));
    }

    private Map<String, String> fullMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("task_key", "任务ID");
        mapping.put("meeting_id", "会议ID");
        mapping.put("title", "待办");
        mapping.put("description", "任务描述");
        mapping.put("meeting_title", "会议名称");
        mapping.put("meeting_time", "会议时间");
        mapping.put("due_date", "截止时间");
        mapping.put("owner", "任务负责人");
        mapping.put("owner_name", "任务负责人信息");
        mapping.put("l1", "L1");
        mapping.put("l2", "L2");
        mapping.put("evidence_location", "证据定位");
        return mapping;
    }

    private BuiltinExecutionContext context(RunRecord run) {
        return new BuiltinExecutionContext(actor(), run, "op-1");
    }

    private RunRecord run(String mode) {
        var fixed = JSON.createObjectNode();
        fixed.put("sourceType", "meeting");
        fixed.put("meetingEventId", "evt-1");
        fixed.put("meetingId", "mtg-1");
        fixed.put("meetingIntegrationId", "integ-1");
        return new RunRecord("run-1", "ent", "conv", "owner", "in", "out", "ver-1", mode, "running", fixed,
            1, 3, 1, false, 1, Instant.EPOCH, null, null, null, null, null, Instant.EPOCH);
    }

    private JsonNode args(String classification, ObjectNode extras) {
        var node = JSON.createObjectNode();
        node.put("meetingId", "mtg-1");
        node.put("taskTitle", "落地对接方案");
        node.put("description", "与业务方完成对接");
        node.put("classification", classification);
        node.put("ownerOpenId", "ou_owner");
        node.put("ownerName", "张三");
        node.put("dueDate", "2031-01-15");
        var evidence = node.putArray("evidence");
        var item = evidence.addObject();
        item.put("sourceType", "minutes");
        item.put("sourceId", "min-1");
        item.put("anchor", "p12");
        node.setAll(extras);
        return node;
    }

    private ObjectNode validExtras() {
        var extras = JSON.createObjectNode();
        extras.put("meetingTitle", "季度经营会");
        extras.set("participantOpenIds", JSON.valueToTree(java.util.List.of("ou_owner", "ou_other")));
        return extras;
    }

    private MeetingEventRow event() {
        var row = new MeetingEventRow();
        row.setId("evt-1");
        row.setEnterpriseId("ent");
        row.setIntegrationId("integ-1");
        row.setEventType("meeting.ended");
        row.setMeetingId("mtg-1");
        row.setStatus("routed");
        row.setRunId("run-1");
        return row;
    }

    private MeetingSubscriptionRow subscription() {
        var row = new MeetingSubscriptionRow();
        row.setId("sub-1");
        row.setEnterpriseId("ent");
        row.setIntegrationId("integ-1");
        row.setStatus("enabled");
        row.setOwnerUserId("owner");
        return row;
    }

    private MeetingBindingRow taskBinding() {
        var row = new MeetingBindingRow();
        row.setId("task-binding");
        row.setEnterpriseId("ent");
        row.setIntegrationId("integ-1");
        row.setBusinessType("task");
        row.setStatus("enabled");
        return row;
    }

    private UserChannelBindingRow owner() {
        var row = new UserChannelBindingRow();
        row.setUserId("owner");
        return row;
    }

    private AuthContext actor() {
        var user = new UserEntity("owner", "user-owner", "pwd", "显示名", "active", false,
            null, null, null, 0L, null, 0L, null, null);
        return new AuthContext(user, "ent", Set.of());
    }
}
