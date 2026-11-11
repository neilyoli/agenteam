package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.plugin.entity.PluginToolRecord;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.plugin.JavaPluginRegistry;
import com.stonewu.agenteam.service.execution.UserQuestionTool;
import com.stonewu.agenteam.service.workspace.WorkspaceToolDefinitions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 会议事件 Run 的工具可见性：只读工具保留，唯一可见的写工具是会议任务发布；
 * 待办、计划等平台写工具对非交互 Run 仍按原规则隐藏。
 */
class ExecutionToolCatalogMeetingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ResourceJson RESOURCE_JSON = new ResourceJson(JSON);

    private final PluginToolMapper tools = mock(PluginToolMapper.class);
    private final ResourceVersionMapper versions = mock(ResourceVersionMapper.class);
    private final WorkspaceToolDefinitions workspace = mock(WorkspaceToolDefinitions.class);
    private final UserQuestionTool questions = mock(UserQuestionTool.class);
    private final ExecutionToolCatalog catalog = new ExecutionToolCatalog(tools,
        new SourceToolDefinitions(RESOURCE_JSON), workspace,
        versions, mock(JavaPluginRegistry.class), questions);

    @Test
    void meetingEventRunSeesOnlyReadToolsAndMeetingPublish() {
        prepareTools();
        var bindings = catalog.list(run("meeting_event"));

        assertTrue(bindings.values().stream().anyMatch(binding -> binding.definition().name()
            .equals("meeting_task_publish")));
        assertTrue(bindings.values().stream().anyMatch(binding -> binding.definition().name()
            .equals("meeting_search")));
        assertFalse(bindings.values().stream().anyMatch(binding -> binding.definition().name()
            .equals("todo_create")), "会议事件 Run 不能看到待办写入工具");
        assertFalse(bindings.values().stream().anyMatch(binding -> binding.definition().name()
            .equals("schedule_create")), "会议事件 Run 不能看到计划写入工具");
        assertFalse(bindings.values().stream().anyMatch(binding -> !binding.readOnly()
            && !binding.definition().name().equals("meeting_task_publish")),
            "会议事件 Run 除会议任务发布外不得有任何写工具");
    }

    @Test
    void interactiveRunStillSeesTodoWriteTools() {
        prepareTools();
        var bindings = catalog.list(run("interactive"));
        assertTrue(bindings.values().stream().anyMatch(binding -> binding.definition().name().equals("todo_create")));
        assertTrue(bindings.values().stream().anyMatch(binding -> binding.definition().name()
            .equals("meeting_task_publish")));
    }

    @Test
    void scheduledRunHidesTodoWriteButKeepsMeetingPublishHiddenToo() {
        prepareTools();
        var bindings = catalog.list(run("scheduled"));
        assertFalse(bindings.values().stream().anyMatch(binding -> binding.definition().name().equals("todo_create")));
        // 计划执行不获得会议写权限：会议发布只对会议事件与交互模式开放。
        assertFalse(bindings.values().stream().anyMatch(binding -> binding.definition().name()
            .equals("meeting_task_publish")));
    }

    private void prepareTools() {
        when(workspace.list(any())).thenReturn(Map.of());
        when(questions.list(any())).thenReturn(Map.of());
        when(versions.available(eq("ent"), anyList())).thenReturn(Set.of("ver-todo", "ver-meeting"));
        when(tools.list("ent", "ver-todo")).thenReturn(List.of(
            new PluginToolRecord("tool-todo", "ent", "ver-todo", true,
                definition("todo_create", "write"))));
        when(tools.list("ent", "ver-meeting")).thenReturn(List.of(
            new PluginToolRecord("tool-publish", "ent", "ver-meeting", true,
                definition("meeting_task_publish", "write")),
            new PluginToolRecord("tool-search", "ent", "ver-meeting", true,
                definition("meeting_search", "read"))));
    }

    private RunRecord run(String mode) {
        var config = JSON.createObjectNode();
        config.putArray("pluginVersionIds").add("ver-todo").add("ver-meeting");
        var executionConfig = JSON.createObjectNode();
        executionConfig.set("config", config);
        var dependencies = executionConfig.putArray("dependencies");
        dependencies.add(dependency("dep-todo", "ver-todo", "待办管理", "todo_management"));
        dependencies.add(dependency("dep-meeting", "ver-meeting", "会议管理", "meeting_management"));
        return new RunRecord("run-1", "ent", "conv", "owner", "in", "out", "ver-agent", mode, "running",
            executionConfig, 1, 3, 1, false, 1, Instant.EPOCH, null, null, null, null, null, Instant.EPOCH);
    }

    private ObjectNode dependency(String resourceId, String versionId, String name, String builtinCode) {
        var dependency = JSON.createObjectNode();
        dependency.put("kind", "plugin");
        dependency.put("resourceId", resourceId);
        dependency.put("versionId", versionId);
        dependency.put("name", name);
        var dependencyConfig = dependency.putObject("config");
        dependencyConfig.put("pluginType", "builtin");
        dependencyConfig.put("builtinCode", builtinCode);
        return dependency;
    }

    private ToolDefinition definition(String name, String operation) {
        var input = RESOURCE_JSON.tree(java.util.Map.of("type", "object"));
        var output = RESOURCE_JSON.tree(java.util.Map.of("type", "object"));
        String hash = RESOURCE_JSON.hash(RESOURCE_JSON.tree(java.util.Map.of("name", name)));
        return new ToolDefinition(name, "说明", hash, input, output, output, operation,
            false, false, false, List.of(), 30);
    }
}
