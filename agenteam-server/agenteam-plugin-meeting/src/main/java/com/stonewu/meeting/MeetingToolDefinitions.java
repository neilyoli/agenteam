package com.stonewu.meeting;

import com.stonewu.tool.api.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * 会议插件的工具清单。全部为 read 类只读操作：读取会议资料与参会人、解析负责人、校验任务候选；
 * 写入（发布任务、通知、确认回调）由宿主受控命令承担，不在此插件暴露。
 */
final class MeetingToolDefinitions {
    private static final int NETWORK_TIMEOUT_SECONDS = 30;

    private MeetingToolDefinitions() {
    }

    static List<ToolSpec> tools() {
        return List.of(
            meetingSearch(),
            meetingGet(),
            meetingArtifactStatus(),
            minutesGet(),
            transcriptGet(),
            peopleResolve(),
            taskValidate()
        );
    }

    private static ToolSpec meetingSearch() {
        return tool("meeting_search", "搜索会议", "按时间范围或会议编号查询授权范围内的飞书会议列表。"
            + "返回会议编号、主题、主持人、起止时间与状态；结果按页返回，pageToken 为空表示第一页。",
            Map.of("startTime", Map.of("type", "string",
                "description", "开始时间下界，ISO-8601 格式（如 2026-11-01T00:00:00+08:00），可省略。"),
                "endTime", Map.of("type", "string",
                    "description", "开始时间上界，ISO-8601 格式，可省略。"),
                "meetingNo", Map.of("type", "string", "description", "会议编号，可省略。"),
                "pageToken", Map.of("type", "string", "description", "翻页游标，空表示第一页。"),
                "pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 100, "default", 20,
                    "description", "每页条数，最多 100。")),
            List.of());
    }

    private static ToolSpec meetingGet() {
        return tool("meeting_get", "读取会议", "读取单个会议的详情与参会人列表。"
            + "返回主持人、参会人（含 openId/userId）、妙记编号与起止时间。",
            Map.of("meetingId", Map.of("type", "string", "description", "会议编号（飞书 meeting id）。")),
            List.of("meetingId"));
    }

    private static ToolSpec meetingArtifactStatus() {
        return tool("meeting_artifact_status", "会议资料就绪状态", "查询会议智能纪要、妙记与逐字稿是否已生成。"
            + "资料未就绪时返回就绪标志与妙记编号，宿主据此持久化等待条件，不占用调用进程。",
            Map.of("meetingId", Map.of("type", "string", "description", "会议编号，与 minuteToken 二选一。"),
                "minuteToken", Map.of("type", "string", "description", "妙记编号，与 meetingId 二选一。")),
            List.of());
    }

    private static ToolSpec minutesGet() {
        return tool("minutes_get", "分段读取纪要", "按页读取会议妙记正文段落（发言人与文本）。"
            + "单次只取一页，hasMore 为 true 时用返回的 pageToken 继续读取，避免突破进程消息上限。",
            Map.of("minuteToken", Map.of("type", "string", "description", "妙记编号。"),
                "pageToken", Map.of("type", "string", "description", "翻页游标，空表示第一页。"),
                "pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 50, "default", 20,
                    "description", "每页段落数，最多 50。")),
            List.of("minuteToken"));
    }

    private static ToolSpec transcriptGet() {
        return tool("transcript_get", "分段读取逐字稿", "按页读取会议逐字稿语句（发言人、文本与时间）。"
            + "单次只取一页，hasMore 为 true 时用返回的 pageToken 继续读取。",
            Map.of("minuteToken", Map.of("type", "string", "description", "妙记编号。"),
                "pageToken", Map.of("type", "string", "description", "翻页游标，空表示第一页。"),
                "pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 50, "default", 20,
                    "description", "每页语句数，最多 50。")),
            List.of("minuteToken"));
    }

    private static ToolSpec peopleResolve() {
        return tool("meeting_people_resolve", "解析负责人", "把提议的负责人姓名或身份映射到参会人列表中的稳定 openId。"
            + "仅凭姓名匹配、同名或多重匹配时保持歧义并由人工确认，绝不自动指派或冒名确认。",
            Map.of("participants", Map.of("type", "array",
                "items", Map.of("type", "object",
                    "properties", Map.of("name", Map.of("type", "string"),
                        "openId", Map.of("type", "string"),
                        "userId", Map.of("type", "string"),
                        "unionId", Map.of("type", "string")),
                    "required", List.of("name"),
                    "additionalProperties", false),
                "description", "会议参会人列表，来自 meeting_get 返回。"),
                "nameHint", Map.of("type", "string", "description", "提议的负责人姓名。"),
                "openIdHint", Map.of("type", "string", "description", "提议的负责人 openId。"),
                "userIdHint", Map.of("type", "string", "description", "提议的负责人 userId。")),
            List.of("participants"));
    }

    private static ToolSpec taskValidate() {
        return tool("task_validate", "校验任务候选", "对结构化任务候选执行业务约束校验：分类必须为 L1/L2/L3，"
            + "进入督办（L1/L2）的任务必须有已确认负责人、可定位证据与建议截止日期。"
            + "返回 valid 与违规原因，语义不确定项标记 needsReview 交由人工复核。",
            Map.of("title", Map.of("type", "string", "description", "任务标题。"),
                "classification", Map.of("type", "string", "enum", List.of("L1", "L2", "L3"),
                    "description", "任务分类。"),
                "owner", Map.of("type", "object",
                    "properties", Map.of("resolvedOpenId", Map.of("type", "string"),
                        "resolvedUserId", Map.of("type", "string"),
                        "displayName", Map.of("type", "string"),
                        "ambiguous", Map.of("type", "boolean"),
                        "reasons", Map.of("type", "array", "items", Map.of("type", "string"))),
                    "description", "负责人候选，来自 meeting_people_resolve。"),
                "evidence", Map.of("type", "array",
                    "items", Map.of("type", "object",
                        "properties", Map.of("sourceType", Map.of("type", "string", "enum", List.of("minutes", "transcript")),
                            "sourceId", Map.of("type", "string"),
                            "anchor", Map.of("type", "string"),
                            "quote", Map.of("type", "string"),
                            "paragraphIndex", Map.of("type", "integer"),
                            "startOffset", Map.of("type", "integer"),
                            "endOffset", Map.of("type", "integer")),
                        "additionalProperties", false),
                    "description", "任务证据列表。"),
                "suggestedDueDate", Map.of("type", "string", "description", "建议截止日期，YYYY-MM-DD。"),
                "needsReview", Map.of("type", "boolean", "description", "是否需人工复核。"),
                "reviewReasons", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "需复核的原因。")),
            List.of("title", "classification"));
    }

    private static ToolSpec tool(String name, String title, String description,
                                 Map<String, Object> properties, List<String> required) {
        return new ToolSpec(name, title, description, "",
            Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false),
            Map.of("type", "object", "properties", Map.of("content", Map.of("type", "string"),
                "isError", Map.of("type", "boolean")), "required", List.of("content", "isError")),
            Map.of(), "read", false, false, true, List.of(), NETWORK_TIMEOUT_SECONDS);
    }
}
