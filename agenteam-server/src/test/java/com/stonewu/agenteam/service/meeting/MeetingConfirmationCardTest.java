package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 确认卡片只校验结构与回调值，不访问飞书或数据库。 */
class MeetingConfirmationCardTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void buildsSchema20CardWithThreeActionsCarryingTaskKeyAndVersion() {
        var card = MeetingConfirmationCard.build("binding-1", "task-42", 7L, "标题", "描述");
        assertEquals("2.0", card.path("schema").asText());
        assertEquals("blue", card.at("/header/template").asText());
        assertEquals("会议任务确认", card.at("/header/title/content").asText());

        assertEquals("markdown", card.at("/body/elements/0/tag").asText());
        assertEquals("hr", card.at("/body/elements/1/tag").asText());
        assertEquals("form", card.at("/body/elements/2/tag").asText());
        assertEquals("confirm_form", card.at("/body/elements/2/name").asText());

        var controls = card.at("/body/elements/2/elements");
        assertEquals(4, controls.size());
        assertEquals("input", controls.get(0).path("tag").asText());

        var confirm = value(card, 1);
        assertEquals("meeting_confirm", confirm.path("action").asText());
        assertEquals("confirm", confirm.path("choice").asText());
        assertEquals("task-42", confirm.path("task_key").asText());
        assertEquals(7L, confirm.path("task_version").asLong());
        assertEquals("binding-1", confirm.path("binding_id").asText());
        assertEquals("revise", value(card, 2).path("choice").asText());
        assertEquals("reject", value(card, 3).path("choice").asText());
    }

    @Test
    void escapesMarkdownSpecialCharacters() {
        var card = MeetingConfirmationCard.build("b", "k", 1L, "标题#1", "**加粗** & 描述");
        assertEquals("标题&#35;1\n\n&#42;&#42;加粗&#42;&#42; &#38; 描述",
            card.at("/body/elements/0/content").asText());
    }

    @Test
    void blankTitleAndDescriptionBecomeSpace() {
        var card = MeetingConfirmationCard.build("b", "k", 1L, null, " ");
        assertEquals(" \n\n ", card.at("/body/elements/0/content").asText());
    }

    @Test
    void recognizesOnlyMeetingActionCallbacks() {
        assertTrue(MeetingConfirmationCard.isMeetingAction(event("meeting_confirm")));
        assertFalse(MeetingConfirmationCard.isMeetingAction(event("answer_question")));
        assertFalse(MeetingConfirmationCard.isMeetingAction(json.createObjectNode()));
    }

    private JsonNode value(JsonNode card, int buttonIndex) {
        return card.at("/body/elements/2/elements/" + buttonIndex + "/behaviors/0/value");
    }

    private JsonNode event(String action) {
        var node = json.createObjectNode();
        node.putObject("event").putObject("action").putObject("value").put("action", action);
        return node;
    }
}
