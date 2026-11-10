package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.meeting.MeetingBitableClient.MeetingRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 督办候选识别的确定性：未确认/逾期/上游未覆盖的判定与证据、负责人解析、无匹配跳过。 */
class MeetingFollowUpClassifierTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String KEY_FIELD = "任务唯一键";
    private static final Map<String, String> MAPPING = Map.of(
        MeetingFieldCode.TITLE.code(), "标题",
        MeetingFieldCode.OWNER.code(), "负责人",
        MeetingFieldCode.CONFIRM_STATUS.code(), "确认状态",
        MeetingFieldCode.EXECUTION_STATUS.code(), "执行状态",
        MeetingFieldCode.DUE_DATE.code(), "截止日期",
        MeetingFieldCode.UPSTREAM_COVERED.code(), "上游覆盖",
        MeetingFieldCode.UNCOVERED_UPSTREAM.code(), "未覆盖上游",
        MeetingFieldCode.EVIDENCE_LOCATION.code(), "证据定位");

    private final MeetingFollowUpClassifier classifier = new MeetingFollowUpClassifier();

    @Test
    void flagsUnconfirmedWhenConfirmStatusBlank() {
        var fields = baseFields().put(KEY_FIELD, "task-1");
        var candidate = classifier.classify(record(fields), MAPPING, KEY_FIELD,
            Set.of(MeetingFollowUpPolicy.UNCONFIRMED), NOW);

        assertTrue(candidate.isPresent());
        assertTrue(candidate.get().reasons().contains(MeetingFollowUpPolicy.UNCONFIRMED));
        assertEquals("task-1", candidate.get().businessKey());
        assertEquals("待确认", candidate.get().evidence().path("confirm_status").asText());
    }

    @Test
    void flagsOverdueWhenDueDatePassedAndNotDone() {
        var fields = baseFields().put(KEY_FIELD, "task-2")
            .put("确认状态", "confirmed")
            .put("截止日期", NOW.minusSeconds(3600).toEpochMilli());
        var candidate = classifier.classify(record(fields), MAPPING, KEY_FIELD,
            Set.of(MeetingFollowUpPolicy.OVERDUE), NOW);

        assertTrue(candidate.isPresent());
        assertTrue(candidate.get().reasons().contains(MeetingFollowUpPolicy.OVERDUE));
    }

    @Test
    void skipsOverdueWhenExecutionAlreadyDone() {
        var fields = baseFields().put(KEY_FIELD, "task-3")
            .put("确认状态", "confirmed")
            .put("截止日期", NOW.minusSeconds(3600).toEpochMilli())
            .put("执行状态", "已完成");
        assertTrue(classifier.classify(record(fields), MAPPING, KEY_FIELD,
            Set.of(MeetingFollowUpPolicy.OVERDUE), NOW).isEmpty());
    }

    @Test
    void flagsUpstreamUncoveredWithEvidence() {
        var fields = baseFields().put(KEY_FIELD, "task-4")
            .put("确认状态", "confirmed")
            .put("上游覆盖", "未覆盖")
            .put("未覆盖上游", "T-2026-0001")
            .put("证据定位", "纪要#3");
        var candidate = classifier.classify(record(fields), MAPPING, KEY_FIELD,
            Set.of(MeetingFollowUpPolicy.UPSTREAM_UNCOVERED), NOW);

        assertTrue(candidate.isPresent());
        assertTrue(candidate.get().reasons().contains(MeetingFollowUpPolicy.UPSTREAM_UNCOVERED));
        assertEquals("T-2026-0001", candidate.get().evidence().path("uncovered_upstream").asText());
        assertEquals("纪要#3", candidate.get().evidence().path("evidence_location").asText());
    }

    @Test
    void resolvesOwnerFromPersonFieldAndSkipsWhenUnmatched() {
        var fields = baseFields().put(KEY_FIELD, "task-5");
        fields.putArray("负责人").addObject().put("id", "ou_owner_1").put("name", "负责人");
        var candidate = classifier.classify(record(fields), MAPPING, KEY_FIELD,
            Set.of(MeetingFollowUpPolicy.UNCONFIRMED), NOW);
        assertEquals("ou_owner_1", candidate.get().ownerSubject());

        // 全部条件都不满足时无候选
        assertTrue(classifier.classify(record(baseFields().put(KEY_FIELD, "task-6").put("确认状态", "confirmed")),
            MAPPING, KEY_FIELD, MeetingFollowUpPolicy.SUPPORTED_CONDITIONS, NOW).isEmpty());
    }

    private ObjectNode baseFields() {
        return JsonNodeFactory.instance.objectNode();
    }

    private MeetingRecord record(JsonNode fields) {
        return new MeetingRecord("rec-1", fields);
    }
}
