package com.stonewu.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.meeting.support.MeetingTestContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加载固定评估集，验证去重、分类合并与上游覆盖的确定性结果，保证可复现。
 */
class MeetingEvalSetTest {
    private final MeetingPlugin plugin = new MeetingPlugin();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reproducesExpectedOutcomesOnFixedEvalSet() throws IOException {
        JsonNode set;
        try (var in = getClass().getResourceAsStream("/eval/meeting-eval-set.json")) {
            assertNotNull(in, "缺少评估集资源");
            set = json.readTree(in);
        }
        assertTrue(set.path("samples").size() >= 3, "评估集样本不足");
        for (JsonNode sample : set.path("samples")) {
            assertSample(sample);
        }
    }

    private void assertSample(JsonNode sample) {
        String id = sample.path("id").asText();
        Map<String, Object> arguments = Map.of(
            "candidates", json.convertValue(sample.path("candidates"), List.class),
            "upstreamTaskKeys", json.convertValue(sample.path("upstreamTaskKeys"), List.class));
        JsonNode result = json.valueToTree(plugin.invoke("task_consolidate", arguments,
            Map.of(), MeetingTestContext.withCredential()));

        assertFalse(result.path("isError").asBoolean(), id + " 不应失败");
        JsonNode expect = sample.path("expect");
        if (expect.hasNonNull("taskCount")) {
            assertEquals(expect.path("taskCount").asInt(), result.path("tasks").size(), id + " 合并条数");
        }
        if (expect.hasNonNull("evidenceCount")) {
            assertEquals(expect.path("evidenceCount").asInt(),
                result.path("tasks").get(0).path("evidence").size(), id + " 证据合并");
        }
        if (expect.hasNonNull("classification")) {
            assertEquals(expect.path("classification").asText(),
                result.path("tasks").get(0).path("classification").asText(), id + " 分类");
        }
        if (expect.hasNonNull("uncoveredUpstream")) {
            List<String> expected = new ArrayList<>();
            expect.path("uncoveredUpstream").forEach(node -> expected.add(node.asText()));
            List<String> actual = new ArrayList<>();
            result.path("uncoveredUpstream").forEach(node -> actual.add(node.asText()));
            assertEquals(expected, actual, id + " 未覆盖上游");
        }
    }
}
