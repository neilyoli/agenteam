package com.stonewu.agenteam.service.schedule.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpScanRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionSubmission;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.service.meeting.MeetingFollowUpStore;
import org.springframework.stereotype.Service;

import java.util.List;

/** 汇总一次发生记录的所有扫描作业结果；只要还有待处理作业即保持 running。 */
@Service
public class MeetingFollowUpResultService {
    private final MeetingFollowUpStore store;
    private final ObjectMapper json;

    public MeetingFollowUpResultService(MeetingFollowUpStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    public ScheduleActionSubmission summarize(ScheduledOccurrenceRow occurrence) {
        ObjectNode result = readResult(occurrence.getActionResultJson());
        List<MeetingFollowUpScanRow> scans = store.scansFor(occurrence.getEnterpriseId(), occurrence.getId());
        int total = scans.size();
        int pending = 0;
        int completed = 0;
        int failed = 0;
        int blocked = 0;
        int candidates = 0;
        int reminded = 0;
        int skipped = 0;
        int sendFailed = 0;
        for (MeetingFollowUpScanRow scan : scans) {
            switch (scan.getStatus()) {
                case "queued" -> pending++;
                case "completed" -> {
                    completed++;
                    JsonNode summary = readResult(scan.getResultJson());
                    candidates += summary.path("candidates").asInt(0);
                    reminded += summary.path("reminded").asInt(0);
                    skipped += summary.path("skipped").asInt(0);
                    sendFailed += summary.path("failed").asInt(0);
                }
                case "failed" -> failed++;
                case "blocked" -> blocked++;
                default -> {
                }
            }
        }
        result.put("scanCount", total).put("pendingCount", pending).put("completedCount", completed)
            .put("failedCount", failed).put("blockedCount", blocked)
            .put("candidates", candidates).put("reminded", reminded)
            .put("skipped", skipped).put("sendFailed", sendFailed);
        String status = pending > 0 ? "running"
            : blocked > 0 && completed == 0 ? "blocked"
            : failed > 0 && completed == 0 ? "failed" : "completed";
        String reason = "blocked".equals(status) ? "MEETING_FOLLOW_UP_BLOCKED"
            : "failed".equals(status) ? "MEETING_FOLLOW_UP_FAILED" : null;
        return new ScheduleActionSubmission(status, reason, null, null, result);
    }

    private ObjectNode readResult(String value) {
        try {
            JsonNode node = json.readTree(value);
            return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : json.createObjectNode();
        } catch (Exception failure) {
            return json.createObjectNode();
        }
    }
}
