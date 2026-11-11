package com.stonewu.agenteam.mapper.plugin;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 会议管理内置插件的工具清单。唯一写操作是经宿主二次校验的 L1/L2 任务发布；
 * 会议事件 Run 只有这一项写工具可自动执行，其余写入一律不可见或不可执行。
 */
@Component
public class MeetingManagementToolDefinitions {
    private final ResourceJson json;

    public MeetingManagementToolDefinitions(ResourceJson json) {
        this.json = json;
    }

    public List<ToolDefinition> tools() {
        return List.of(taskPublish(), taskSearch(), upstreamSearch());
    }

    private ToolDefinition taskPublish() {
        var evidenceItem = json.tree(Map.of("type", "object", "additionalProperties", false,
            "required", List.of("sourceType", "anchor"),
            "properties", Map.of(
                "sourceType", Map.of("type", "string", "enum", List.of("minutes", "transcript")),
                "sourceId", text(191),
                "anchor", text(200),
                "quote", text(500),
                "paragraphIndex", Map.of("type", "integer"),
                "startOffset", Map.of("type", "integer"),
                "endOffset", Map.of("type", "integer"))));
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("meetingId", text(191));
        properties.put("meetingTitle", text(300));
        properties.put("meetingTime", text(64));
        properties.put("taskTitle", text(200));
        properties.put("description", text(2000));
        properties.put("classification", Map.of("type", "string", "enum", List.of("L1", "L2", "L3")));
        properties.put("deliverable", text(500));
        properties.put("acceptanceCriteria", text(500));
        properties.put("ownerOpenId", text(191));
        properties.put("ownerName", text(100));
        properties.put("dueDate", Map.of("type", "string", "pattern", "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"));
        properties.put("evidence", Map.of("type", "array", "minItems", 1, "maxItems", 10, "items", evidenceItem));
        properties.put("participantOpenIds", Map.of("type", "array", "maxItems", 500, "items", text(191)));
        properties.put("departmentCode", text(100));
        properties.put("collaboratorOpenIds", Map.of("type", "array", "maxItems", 20, "items", text(191)));
        properties.put("priority", text(50));
        properties.put("riskLevel", text(50));
        properties.put("upstreamTaskRefs", Map.of("type", "array", "maxItems", 20, "items", text(100),
            "description", "上游任务编号列表，必须是 meeting_task_search 或 meeting_upstream_search 返回的真实 task_key。"));
        properties.put("existingTaskKey", Map.of("type", "string", "maxLength", 100,
            "description", "跨会议再次提及或修订同一任务时引用的既有任务编号（task_key）。"));
        properties.put("sourceMaterial", text(200));
        var input = json.tree(Map.of("type", "object", "additionalProperties", false,
            "required", List.of("meetingId", "taskTitle", "classification", "ownerOpenId", "ownerName",
                "dueDate", "evidence"),
            "properties", properties));
        var output = json.tree(Map.of("type", "object", "additionalProperties", false,
            "required", List.of("status", "content", "isError"),
            "properties", Map.of(
                "status", Map.of("type", "string", "enum", List.of("queued", "skipped")),
                "businessKey", text(191),
                "commandId", text(100),
                "content", Map.of("type", "string"),
                "isError", Map.of("type", "boolean"))));
        String hash = json.hash(json.tree(Map.of("name", "meeting_task_publish", "inputSchema", input,
            "outputSchema", output, "operationClass", "write")));
        return new ToolDefinition("meeting_task_publish",
            "发布会议任务到企业督办宽表。一次提交一条经校验的任务：仅 L1/L2 可发布（L3 返回 skipped），"
                + "L1/L2 必须有真实负责人 openId、可定位纪要/逐字稿证据与截止日期；负责人必须已在企业绑定飞书账号"
                + "且属于会议参会人。返回 queued 只表示已进入飞书同步队列，不代表已写入飞书。",
            hash, input, output, json.tree(Map.of("readOnlyHint", false)), "write", false, false, false, List.of(), 60);
    }

    private ToolDefinition taskSearch() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("query", text(200));
        properties.put("meetingId", text(191));
        properties.put("ownerOpenId", text(191));
        properties.put("taskKey", text(100));
        properties.put("includeLiveStatus", Map.of("type", "boolean", "default", false));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 20, "default", 10));
        var input = json.tree(Map.of("type", "object", "additionalProperties", false,
            "properties", properties));
        var output = json.tree(Map.of("type", "object", "additionalProperties", false,
            "required", List.of("content", "isError"),
            "properties", Map.of(
                "items", Map.of("type", "array", "items", Map.of("type", "object")),
                "content", Map.of("type", "string"),
                "isError", Map.of("type", "boolean"))));
        String hash = json.hash(json.tree(Map.of("name", "meeting_task_search", "inputSchema", input,
            "outputSchema", output, "operationClass", "read")));
        return new ToolDefinition("meeting_task_search",
            "检索企业会议任务：按标题关键词、会议编号、负责人或任务编号查询历史任务与来源会议。"
                + "发布前先检索，判断是否已有同一任务；跨会议再次提及时把返回的 task_key 作为 existingTaskKey 引用。"
                + "includeLiveStatus 为 true 时附带飞书任务宽表最新状态（任务状态/标题/负责人）。",
            hash, input, output, json.tree(Map.of("readOnlyHint", true)), "read", false, false, false, List.of(), 60);
    }

    private ToolDefinition upstreamSearch() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("meetingId", text(191));
        properties.put("query", text(200));
        properties.put("ownerOpenId", text(191));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 20, "default", 10));
        var input = json.tree(Map.of("type", "object", "additionalProperties", false,
            "properties", properties));
        var output = json.tree(Map.of("type", "object", "additionalProperties", false,
            "required", List.of("content", "isError"),
            "properties", Map.of(
                "items", Map.of("type", "array", "items", Map.of("type", "object")),
                "content", Map.of("type", "string"),
                "isError", Map.of("type", "boolean"))));
        String hash = json.hash(json.tree(Map.of("name", "meeting_upstream_search", "inputSchema", input,
            "outputSchema", output, "operationClass", "read")));
        return new ToolDefinition("meeting_upstream_search",
            "检索上游任务候选：查找当前会议之外（通常更早会议）提及的任务，用于判断本会议任务是否覆盖既有承诺。"
                + "返回候选任务的 task_key、标题、负责人与来源会议；未检索到时如实返回空列表，"
                + "不能把“没有检索到”直接当作“明确未覆盖”。",
            hash, input, output, json.tree(Map.of("readOnlyHint", true)), "read", false, false, false, List.of(), 60);
    }

    private Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "maxLength", maximum);
    }
}
