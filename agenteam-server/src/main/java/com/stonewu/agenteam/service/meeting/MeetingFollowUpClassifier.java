package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.meeting.MeetingBitableClient.MeetingRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 确定性督办候选识别：按跟进条件判断一条飞书任务是否需催办，并附来源证据。
 * 飞书字段值形状（日期毫秒、人员对象、单选标记）以真实租户为准，M7 现场校准。
 */
@Component
public class MeetingFollowUpClassifier {
    private static final Set<String> DONE_MARKERS = Set.of("已完成", "completed", "done");
    private static final Set<String> UNCOVERED_MARKERS = Set.of("未覆盖", "uncovered", "false");

    public Optional<MeetingFollowUpCandidate> classify(MeetingRecord record, Map<String, String> codeToFeishu,
                                                       String keyField, Set<String> conditions, Instant now) {
        JsonNode fields = record.fields();
        String businessKey = text(fields.get(keyField));
        if (isBlank(businessKey)) {
            return Optional.empty();
        }
        String title = text(fields.get(codeToFeishu.get(MeetingFieldCode.TITLE.code())));
        String ownerSubject = person(fields.get(codeToFeishu.get(MeetingFieldCode.OWNER.code())));

        List<String> reasons = new ArrayList<>();
        ObjectNode evidence = JsonNodeFactory.instance.objectNode();
        evidence.put("task_key", businessKey);
        if (!isBlank(title)) {
            evidence.put("title", title);
        }

        String confirmStatus = text(fields.get(codeToFeishu.get(MeetingFieldCode.CONFIRM_STATUS.code())));
        if (conditions.contains(MeetingFollowUpPolicy.UNCONFIRMED) && isBlank(confirmStatus)) {
            reasons.add(MeetingFollowUpPolicy.UNCONFIRMED);
            evidence.put("confirm_status", "待确认");
        }

        String executionStatus = text(fields.get(codeToFeishu.get(MeetingFieldCode.EXECUTION_STATUS.code())));
        Instant due = date(fields.get(codeToFeishu.get(MeetingFieldCode.DUE_DATE.code())));
        if (due != null) {
            evidence.put("due_date", due.toString());
        }
        if (conditions.contains(MeetingFollowUpPolicy.OVERDUE) && due != null && due.isBefore(now)
            && !DONE_MARKERS.contains(normalize(executionStatus))) {
            reasons.add(MeetingFollowUpPolicy.OVERDUE);
            evidence.put("execution_status", executionStatus == null ? "" : executionStatus);
        }

        String covered = text(fields.get(codeToFeishu.get(MeetingFieldCode.UPSTREAM_COVERED.code())));
        String uncoveredUpstream = text(fields.get(codeToFeishu.get(MeetingFieldCode.UNCOVERED_UPSTREAM.code())));
        if (conditions.contains(MeetingFollowUpPolicy.UPSTREAM_UNCOVERED)
            && (isUncovered(covered) || !isBlank(uncoveredUpstream))) {
            reasons.add(MeetingFollowUpPolicy.UPSTREAM_UNCOVERED);
            if (!isBlank(covered)) {
                evidence.put("upstream_covered", covered);
            }
            if (!isBlank(uncoveredUpstream)) {
                evidence.put("uncovered_upstream", uncoveredUpstream);
            }
        }

        String evidenceLocation = text(fields.get(codeToFeishu.get(MeetingFieldCode.EVIDENCE_LOCATION.code())));
        if (!isBlank(evidenceLocation)) {
            evidence.put("evidence_location", evidenceLocation);
        }

        if (reasons.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MeetingFollowUpCandidate(businessKey, title, ownerSubject, reasons, evidence));
    }

    private static boolean isUncovered(String covered) {
        return !isBlank(covered) && UNCOVERED_MARKERS.contains(normalize(covered));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 文本/数字/选项字段的字符串表示；多值文本取首个可读片段。 */
    private static String text(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber()) {
            return value.asText();
        }
        if (value.isObject() && value.path("text").isTextual()) {
            return value.path("text").asText();
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                String text = text(item);
                if (!isBlank(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    /** 人员字段常见形状：[{"id":"ou_xxx"}]、{"id":"ou_xxx"} 或纯 open_id 文本；无法解析返回 null。 */
    private static String person(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                String subject = person(item);
                if (!isBlank(subject)) {
                    return subject;
                }
            }
            return null;
        }
        if (value.isObject()) {
            String id = value.path("id").asText(null);
            if (!isBlank(id)) {
                return id;
            }
            String openId = value.path("open_id").asText(null);
            if (!isBlank(openId)) {
                return openId;
            }
        }
        return null;
    }

    /** 日期字段常见形状：毫秒时间戳（秒级容错）或 ISO 文本；无法解析返回 null。 */
    private static Instant date(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            long epoch = value.asLong();
            if (epoch > 0 && epoch < 100_000_000_000L) {
                epoch *= 1000;
            }
            return Instant.ofEpochMilli(epoch);
        }
        if (value.isTextual()) {
            try {
                return Instant.parse(value.asText());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }
}
