package com.stonewu.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.meeting.support.MeetingTestContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MeetingPluginTest {
    private final MeetingPlugin plugin = new MeetingPlugin();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exposesOnlyReadOnlyMeetingTools() {
        var tools = plugin.tools();
        assertEquals(Set.of("meeting_search", "meeting_get", "meeting_artifact_status", "minutes_get",
                "transcript_get", "meeting_people_resolve", "task_validate"),
            tools.stream().map(t -> t.name()).collect(Collectors.toSet()));
        for (var tool : tools) {
            assertEquals("read", tool.operationClass());
            assertFalse(tool.supportsDeduplication());
            assertFalse(tool.supportsResultQuery());
            assertTrue(tool.supportsCancel());
        }
    }

    @Test
    void resolvesUnambiguousOwnerByOpenId() {
        var result = invoke("meeting_people_resolve", Map.of(
            "participants", List.of(participant("ou_1", "张三"), participant("ou_2", "李四")),
            "openIdHint", "ou_1"));
        assertFalse(result.path("isError").asBoolean());
        assertFalse(result.path("resolved").path("ambiguous").asBoolean());
        assertEquals("ou_1", result.path("resolved").path("resolvedOpenId").asText());
    }

    @Test
    void flagsAmbiguousWhenDuplicateNames() {
        var result = invoke("meeting_people_resolve", Map.of(
            "participants", List.of(participant("ou_1", "张三"), participant("ou_2", "张三")),
            "nameHint", "张三"));
        assertFalse(result.path("isError").asBoolean());
        assertTrue(result.path("resolved").path("ambiguous").asBoolean());
        assertFalse(result.path("resolved").path("reasons").isEmpty());
    }

    @Test
    void flagsAmbiguousWhenOwnerNotAmongParticipants() {
        var result = invoke("meeting_people_resolve", Map.of(
            "participants", List.of(participant("ou_1", "张三")),
            "openIdHint", "ou_unknown"));
        assertTrue(result.path("resolved").path("ambiguous").asBoolean());
    }

    @Test
    void validatesL1RequiresOwnerEvidenceAndDueDate() {
        var result = invoke("task_validate", Map.of(
            "title", "对账口径统一", "classification", "L1"));
        assertFalse(result.path("isError").asBoolean());
        assertFalse(result.path("valid").asBoolean());
        var violations = result.path("violations");
        assertTrue(violations.toString().contains("负责人"));
        assertTrue(violations.toString().contains("证据"));
        assertTrue(violations.toString().contains("截止日期"));
    }

    @Test
    void acceptsCompleteL2Proposal() {
        var result = invoke("task_validate", Map.of(
            "title", "对账口径统一", "classification", "L2",
            "owner", Map.of("resolvedOpenId", "ou_1", "displayName", "张三", "ambiguous", false),
            "evidence", List.of(Map.of("sourceType", "minutes", "sourceId", "ob_1",
                "anchor", "minutes#3", "quote", "张三：11 月底前完成对账口径统一。")),
            "suggestedDueDate", "2026-11-30"));
        assertFalse(result.path("isError").asBoolean());
        assertTrue(result.path("valid").asBoolean());
        assertFalse(result.path("needsReview").asBoolean());
    }

    @Test
    void acceptsL3WithoutSupervisionRequirements() {
        var result = invoke("task_validate", Map.of(
            "title", "整理周报", "classification", "L3"));
        assertTrue(result.path("valid").asBoolean());
    }

    @Test
    void rejectsUnknownClassification() {
        var result = invoke("task_validate", Map.of("title", "标题", "classification", "X"));
        assertFalse(result.path("valid").asBoolean());
        assertTrue(result.path("violations").toString().contains("分类"));
    }

    @Test
    void rejectsEvidenceWithUnknownSourceType() {
        var result = invoke("task_validate", Map.of(
            "title", "标题", "classification", "L1",
            "owner", Map.of("resolvedOpenId", "ou_1", "ambiguous", false),
            "evidence", List.of(Map.of("sourceType", "email", "quote", "来自邮件")),
            "suggestedDueDate", "2026-11-30"));
        assertTrue(result.path("isError").asBoolean());
        assertEquals("INVALID_ARGUMENT", result.path("code").asText());
    }

    @Test
    void returnsAuthExpiredWhenNoCredential() {
        var result = invoke("meeting_search", Map.of(), MeetingTestContext.withoutCredential());
        assertTrue(result.path("isError").asBoolean());
        assertEquals("AUTH_EXPIRED", result.path("code").asText());
    }

    @Test
    void rejectsMissingRequiredArgument() {
        var result = invoke("meeting_get", Map.of());
        assertTrue(result.path("isError").asBoolean());
        assertEquals("INVALID_ARGUMENT", result.path("code").asText());
    }

    @Test
    void propagatesCancellationInsteadOfSwallowingIt() {
        assertThrows(IllegalStateException.class, () -> plugin.invoke("meeting_people_resolve",
            Map.of("participants", List.of(participant("ou_1", "张三"))),
            Map.of(), MeetingTestContext.cancellingAfter(1)));
    }

    private JsonNode invoke(String tool, Map<String, Object> arguments) {
        return invoke(tool, arguments, MeetingTestContext.withCredential());
    }

    private JsonNode invoke(String tool, Map<String, Object> arguments, MeetingTestContext context) {
        return json.valueToTree(plugin.invoke(tool, arguments, Map.of("timeoutSeconds", 5), context));
    }

    private static Map<String, Object> participant(String openId, String name) {
        return Map.of("openId", openId, "name", name);
    }
}
