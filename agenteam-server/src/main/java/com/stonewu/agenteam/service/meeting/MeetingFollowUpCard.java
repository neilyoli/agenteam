package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/** 会议任务催办卡片；只做提醒与来源证据展示，不携带可冒充确认的回调动作。 */
public final class MeetingFollowUpCard {
    private static final Map<String, String> REASON_LABELS = Map.of(
        MeetingFollowUpPolicy.UNCONFIRMED, "尚未确认",
        MeetingFollowUpPolicy.OVERDUE, "已逾期",
        MeetingFollowUpPolicy.UPSTREAM_UNCOVERED, "上游承诺未覆盖");

    private MeetingFollowUpCard() {
    }

    public static ObjectNode build(String title, List<String> reasons, JsonNode evidence) {
        ObjectNode card = JsonNodeFactory.instance.objectNode().put("schema", "2.0");
        var header = card.putObject("header").put("template", "orange");
        header.putObject("title").put("tag", "plain_text").put("content", "会议任务催办");
        card.putObject("body").putArray("elements").addObject()
            .put("tag", "markdown").put("content", content(title, reasons, evidence));
        return card;
    }

    private static String content(String title, List<String> reasons, JsonNode evidence) {
        var markdown = new StringBuilder();
        if (title != null && !title.isBlank()) {
            markdown.append("**").append(safe(title)).append("**\n");
        }
        for (String reason : reasons) {
            String label = REASON_LABELS.getOrDefault(reason, reason);
            markdown.append("- ").append(label).append('\n');
        }
        String due = evidence == null ? null : evidence.path("due_date").asText(null);
        if (due != null && !due.isBlank()) {
            markdown.append("截止时间：").append(safe(due)).append('\n');
        }
        String location = evidence == null ? null : evidence.path("evidence_location").asText(null);
        if (location != null && !location.isBlank()) {
            markdown.append("来源证据：").append(safe(location)).append('\n');
        }
        return markdown.toString();
    }

    private static String safe(String text) {
        if (text == null || text.isBlank()) {
            return " ";
        }
        var result = new StringBuilder();
        text.codePoints().forEach(point -> {
            if ("&<>\\*_`[]#~!".indexOf(point) >= 0) {
                result.append("&#").append(point).append(';');
            } else {
                result.appendCodePoint(point);
            }
        });
        return result.toString();
    }
}
