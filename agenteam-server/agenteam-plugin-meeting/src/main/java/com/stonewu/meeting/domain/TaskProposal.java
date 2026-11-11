package com.stonewu.meeting.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 结构化任务候选：Agent 提议、Java Validator 强制守住业务约束后，交给宿主受控命令发布。
 * 缺失字段保持空值；负责人歧义、缺证据或缺截止日期时由 Validator 阻断或转待澄清。
 */
public record TaskProposal(
    String title,
    String description,
    TaskClassification classification,
    String deliverable,
    String acceptanceCriteria,
    OwnerCandidate owner,
    List<String> collaborators,
    String departmentCode,
    LocalDate suggestedDueDate,
    String priority,
    String riskLevel,
    List<TaskEvidence> evidence,
    List<String> upstreamTaskRefs,
    boolean needsReview,
    List<String> reviewReasons) {
}
