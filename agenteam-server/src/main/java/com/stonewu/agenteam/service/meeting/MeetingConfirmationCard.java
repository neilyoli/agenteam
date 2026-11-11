package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 会议任务确认卡片；回调值携带稳定 task_key、任务版本与绑定，供可信回调做身份与版本校验。 */
public final class MeetingConfirmationCard {
    public static final String ACTION = "meeting_confirm";

    private MeetingConfirmationCard() {
    }

    public static ObjectNode build(String bindingId, String businessKey, long taskVersion,
                                   String title, String description) {
        ObjectNode card = JsonNodeFactory.instance.objectNode().put("schema", "2.0");
        var header = card.putObject("header").put("template", "blue");
        header.putObject("title").put("tag", "plain_text").put("content", "会议任务确认");
        ArrayNode elements = card.putObject("body").putArray("elements");
        elements.addObject().put("tag", "markdown").put("content", safe(title) + "\n\n" + safe(description));
        elements.addObject().put("tag", "hr");
        ArrayNode controls = elements.addObject().put("tag", "form").put("name", "confirm_form").putArray("elements");
        var input = controls.addObject().put("tag", "input").put("name", "comment").put("input_type", "multiline_text")
            .put("required", false).put("width", "fill").put("max_length", 1000).put("rows", 2)
            .put("auto_resize", true).put("max_rows", 6).put("label_position", "top");
        input.putObject("label").put("tag", "plain_text").put("content", "调整意见或拒绝原因");
        input.putObject("placeholder").put("tag", "plain_text").put("content", "可选，最多 1000 个字符");
        for (MeetingConfirmationAction action : MeetingConfirmationAction.values()) {
            button(controls, action, bindingId, businessKey, taskVersion);
        }
        return card;
    }

    /** 判断飞书卡片回调是否属于会议确认操作。 */
    public static boolean isMeetingAction(JsonNode event) {
        return ACTION.equals(event.path("event").path("action").path("value").path("action").asText());
    }

    private static void button(ArrayNode controls, MeetingConfirmationAction action,
                               String bindingId, String businessKey, long taskVersion) {
        String label = switch (action) {
            case CONFIRM -> "确认接受";
            case REVISE -> "需调整";
            case REJECT -> "拒绝";
        };
        String type = switch (action) {
            case CONFIRM -> "primary";
            case REVISE -> "default";
            case REJECT -> "danger";
        };
        var button = controls.addObject().put("tag", "button").put("type", type).put("name", "submit_" + action.value())
            .put("action_type", "form_submit");
        button.putObject("text").put("tag", "plain_text").put("content", label);
        button.putArray("behaviors").addObject().put("type", "callback").putObject("value")
            .put("action", ACTION).put("choice", action.value())
            .put("task_key", businessKey).put("task_version", taskVersion).put("binding_id", bindingId);
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
