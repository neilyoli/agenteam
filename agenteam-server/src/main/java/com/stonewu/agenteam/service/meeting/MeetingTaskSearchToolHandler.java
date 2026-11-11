package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.service.plugin.PlatformToolHandler;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 会议任务检索的只读工具处理器。检索范围固定为当前企业，身份由宿主执行上下文提供，
 * 不接收模型传入的企业或用户身份。
 */
@Service
public class MeetingTaskSearchToolHandler implements PlatformToolHandler {
    private final MeetingTaskQueryService queries;

    public MeetingTaskSearchToolHandler(MeetingTaskQueryService queries) {
        this.queries = queries;
    }

    @Override
    public Set<String> toolNames() {
        return Set.of("meeting_task_search", "meeting_upstream_search");
    }

    @Override
    public JsonNode call(BuiltinExecutionContext context, String name, JsonNode arguments) {
        String enterprise = context.actor().enterpriseId();
        return switch (name) {
            case "meeting_task_search" -> queries.search(enterprise,
                text(arguments, "taskKey"), text(arguments, "query"), text(arguments, "meetingId"),
                text(arguments, "ownerOpenId"), arguments.path("includeLiveStatus").asBoolean(false),
                arguments.path("limit").asInt(10));
            case "meeting_upstream_search" -> queries.upstreamSearch(enterprise,
                text(arguments, "meetingId"), text(arguments, "query"), text(arguments, "ownerOpenId"),
                arguments.path("limit").asInt(10));
            default -> throw new IllegalArgumentException("会议检索工具没有登记：" + name);
        };
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
