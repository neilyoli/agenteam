package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingEventMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSubscriptionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 会议事件 Run 自动发布的唯一授权判定：全部条件满足才放行，任一变化即拒绝。 */
class MeetingRunExecutionPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MeetingEventMapper events = mock(MeetingEventMapper.class);
    private final MeetingSubscriptionMapper subscriptions = mock(MeetingSubscriptionMapper.class);
    private final MeetingBindingMapper bindings = mock(MeetingBindingMapper.class);
    private final MeetingEventPolicy eventPolicy = mock(MeetingEventPolicy.class);
    private final MeetingEventAccessService access = mock(MeetingEventAccessService.class);
    private final MeetingRunExecutionPolicy policy =
        new MeetingRunExecutionPolicy(events, subscriptions, bindings, eventPolicy, access);

    @Test
    void allowsPublishForValidMeetingEventRun() {
        prepareValidContext();
        assertTrue(policy.autoPublishAllowed(run(), publishBinding()));
        var authorization = policy.requireAutoPublish(run());
        assertEquals("evt-1", authorization.event().getId());
        assertEquals("task-binding", authorization.taskBinding().getId());
    }

    @Test
    void rejectsNonMeetingModesAndOtherTools() {
        prepareValidContext();
        var interactive = new RunRecord("run-1", "ent", "conv", "owner", "in", "out", "ver-1", "interactive",
            "running", fixed(), 1, 3, 1, false, 1, Instant.EPOCH, null, null, null, null, null, Instant.EPOCH);
        assertFalse(policy.autoPublishAllowed(interactive, publishBinding()));
        assertFalse(policy.autoPublishAllowed(run(), todoCreateBinding()));
    }

    @Test
    void rejectsMissingOrDetachedEvent() {
        when(events.selectOne(any())).thenReturn(null);
        assertFalse(policy.autoPublishAllowed(run(), publishBinding()));

        // 事件存在但关联了其他 Run：拒绝，防止事件被第二个执行复用。
        var detached = event();
        detached.setRunId("run-other");
        when(events.selectOne(any())).thenReturn(detached);
        assertFalse(policy.autoPublishAllowed(run(), publishBinding()));
    }

    @Test
    void rejectsDisabledOrChangedSubscription() {
        prepareValidContext();
        var disabled = subscription();
        disabled.setStatus("disabled");
        when(subscriptions.selectOne(any())).thenReturn(disabled);
        assertFalse(policy.autoPublishAllowed(run(), publishBinding()));

        prepareValidContext();
        var changedHire = subscription();
        changedHire.setHireId("hire-other");
        when(subscriptions.selectOne(any())).thenReturn(changedHire);
        assertFalse(policy.autoPublishAllowed(run(), publishBinding()));

        prepareValidContext();
        var upgraded = subscription();
        upgraded.setPluginVersion("2.0.0");
        when(subscriptions.selectOne(any())).thenReturn(upgraded);
        assertFalse(policy.autoPublishAllowed(run(), publishBinding()));
    }

    @Test
    void rejectsInactivePluginOrUnavailableOwner() {
        prepareValidContext();
        when(eventPolicy.pluginActive("ent", "1.0.0")).thenReturn(false);
        assertFalse(policy.autoPublishAllowed(run(), publishBinding()));

        prepareValidContext();
        when(access.requireOwner(any())).thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
            "MEETING_OWNER_UNAVAILABLE", "订阅配置者的账号或成员资格已不可用。"));
        assertFalse(policy.autoPublishAllowed(run(), publishBinding()));
    }

    @Test
    void rejectsMissingOrCrossIntegrationTaskBinding() {
        prepareValidContext();
        when(bindings.selectOne(any())).thenReturn(null);
        assertFalse(policy.autoPublishAllowed(run(), publishBinding()));

        prepareValidContext();
        var foreign = taskBinding();
        foreign.setIntegrationId("integ-other");
        when(bindings.selectOne(any())).thenReturn(foreign);
        assertFalse(policy.autoPublishAllowed(run(), publishBinding()));
    }

    @Test
    void requireAutoPublishFailsWithAuthorizationCode() {
        when(events.selectOne(any())).thenReturn(null);
        var failure = assertThrows(ApiException.class, () -> policy.requireAutoPublish(run()));
        assertEquals("MEETING_RUN_AUTHORIZATION_INVALID", failure.code());
    }

    private void prepareValidContext() {
        when(events.selectOne(any())).thenReturn(event());
        when(subscriptions.selectOne(any())).thenReturn(subscription());
        when(bindings.selectOne(any())).thenReturn(taskBinding());
        when(eventPolicy.pluginActive("ent", "1.0.0")).thenReturn(true);
        when(access.requireOwner(any())).thenReturn(actor());
    }

    private RunRecord run() {
        return new RunRecord("run-1", "ent", "conv", "owner", "in", "out", "ver-1", "meeting_event",
            "running", fixed(), 1, 3, 1, false, 1, Instant.EPOCH, null, null, null, null, null, Instant.EPOCH);
    }

    private ObjectNode fixed() {
        var node = JSON.createObjectNode();
        node.put("sourceType", "meeting");
        node.put("meetingEventId", "evt-1");
        node.put("meetingEventType", "meeting.ended");
        node.put("meetingId", "mtg-1");
        node.put("meetingSubscriptionId", "sub-1");
        node.put("meetingHireId", "hire-1");
        node.put("meetingPluginVersion", "1.0.0");
        node.put("meetingIntegrationId", "integ-1");
        node.put("meetingSelectedVersionId", "ver-1");
        return node;
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
        row.setEventType("meeting.ended");
        row.setStatus("enabled");
        row.setHireId("hire-1");
        row.setAgentVersionId(null);
        row.setPluginVersion("1.0.0");
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

    private ExecutionToolBinding publishBinding() {
        return binding("meeting_task_publish", "write", meetingConfig());
    }

    private ExecutionToolBinding todoCreateBinding() {
        var config = meetingConfig();
        config.put("builtinCode", "todo_management");
        return binding("todo_create", "write", config);
    }

    private ObjectNode meetingConfig() {
        var config = JSON.createObjectNode();
        config.put("pluginType", "builtin");
        config.put("builtinCode", "meeting_management");
        return config;
    }

    private ExecutionToolBinding binding(String name, String operation, ObjectNode config) {
        var input = JSON.createObjectNode();
        var output = JSON.createObjectNode();
        var definition = new ToolDefinition(name, "说明", "hash", input, output, output, operation,
            false, false, false, java.util.List.of(), 30);
        return new ExecutionToolBinding("res-1", "ver-1", "plugin", "会议管理", "tool-1", definition, config);
    }

    private AuthContext actor() {
        var user = new UserEntity("owner", "user-owner", "pwd", "显示名", "active", false,
            null, null, null, 0L, null, 0L, null, null);
        return new AuthContext(user, "ent", Set.of());
    }
}
