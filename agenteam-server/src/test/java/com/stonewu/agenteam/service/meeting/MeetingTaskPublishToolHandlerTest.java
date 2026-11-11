package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 会议工具处理器只登记会议任务发布并委托发布服务，不自行处理身份。 */
class MeetingTaskPublishToolHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MeetingTaskPublicationService publication = mock(MeetingTaskPublicationService.class);
    private final MeetingTaskPublishToolHandler handler = new MeetingTaskPublishToolHandler(publication);

    @Test
    void registersOnlyMeetingTaskPublish() {
        assertEquals(Set.of("meeting_task_publish"), handler.toolNames());
    }

    @Test
    void delegatesWithContextAndArguments() {
        var context = new BuiltinExecutionContext(actor(), run(), "op-9");
        var arguments = JSON.createObjectNode().put("taskTitle", "任务");
        var expected = JSON.createObjectNode().put("status", "queued");
        when(publication.publish(context, arguments)).thenReturn(expected);

        JsonNode result = handler.call(context, "meeting_task_publish", arguments);

        assertSame(expected, result);
        verify(publication).publish(context, arguments);
    }

    private RunRecord run() {
        return new RunRecord("run-1", "ent", "conv", "owner", "in", "out", "ver-1", "meeting_event",
            "running", JSON.createObjectNode(), 1, 3, 1, false, 1, Instant.EPOCH, null, null, null, null, null,
            Instant.EPOCH);
    }

    private AuthContext actor() {
        var user = new UserEntity("owner", "user-owner", "pwd", "显示名", "active", false,
            null, null, null, 0L, null, 0L, null, null);
        return new AuthContext(user, "ent", Set.of());
    }
}
