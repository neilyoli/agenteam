package com.stonewu.meeting.service;

import com.stonewu.meeting.domain.OwnerCandidate;
import com.stonewu.meeting.domain.TaskClassification;
import com.stonewu.meeting.domain.TaskEvidence;
import com.stonewu.meeting.domain.TaskProposal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 任务合并与上游覆盖：把同一会议（或跨会议）提取出的候选按稳定 task_key 去重合并，
 * 并依据候选声明的 upstreamTaskRefs 判定上游承诺是否被覆盖。合并规则确定，供审计与复现。
 */
public final class TaskConsolidationService {
    private final TaskKeyService keys = new TaskKeyService();

    public record ConsolidatedTask(String taskKey, TaskProposal proposal) {
    }

    public record Result(List<ConsolidatedTask> tasks, List<String> coveredUpstream, List<String> uncoveredUpstream) {
        public Result {
            tasks = List.copyOf(tasks);
            coveredUpstream = List.copyOf(coveredUpstream);
            uncoveredUpstream = List.copyOf(uncoveredUpstream);
        }
    }

    public Result consolidate(List<TaskProposal> proposals, List<String> upstreamTaskKeys) {
        Map<String, List<TaskProposal>> groups = new LinkedHashMap<>();
        if (proposals != null) {
            for (TaskProposal proposal : proposals) {
                if (proposal == null) {
                    continue;
                }
                groups.computeIfAbsent(keys.compute(proposal.title(), ownerOpenId(proposal)), ignored -> new ArrayList<>())
                    .add(proposal);
            }
        }

        List<ConsolidatedTask> tasks = new ArrayList<>(groups.size());
        LinkedHashSet<String> covered = new LinkedHashSet<>();
        for (var entry : groups.entrySet()) {
            TaskProposal merged = merge(entry.getValue());
            tasks.add(new ConsolidatedTask(entry.getKey(), merged));
            if (merged.upstreamTaskRefs() != null) {
                covered.addAll(merged.upstreamTaskRefs());
            }
        }

        List<String> uncovered = new ArrayList<>();
        if (upstreamTaskKeys != null) {
            for (String key : upstreamTaskKeys) {
                if (key != null && !key.isBlank() && !covered.contains(key)) {
                    uncovered.add(key);
                }
            }
        }
        return new Result(tasks, new ArrayList<>(covered), uncovered);
    }

    private static String ownerOpenId(TaskProposal proposal) {
        OwnerCandidate owner = proposal.owner();
        return owner == null ? null : owner.resolvedOpenId();
    }

    private TaskProposal merge(List<TaskProposal> group) {
        TaskProposal first = group.get(0);
        TaskClassification classification = first.classification();
        OwnerCandidate owner = first.owner();
        LocalDate dueDate = first.suggestedDueDate();
        LinkedHashSet<String> collaborators = new LinkedHashSet<>();
        LinkedHashSet<TaskEvidence> evidence = new LinkedHashSet<>();
        LinkedHashSet<String> upstreamRefs = new LinkedHashSet<>();
        LinkedHashSet<String> reviewReasons = new LinkedHashSet<>();
        boolean needsReview = first.needsReview();
        String description = first.description();
        String deliverable = first.deliverable();
        String acceptanceCriteria = first.acceptanceCriteria();
        String departmentCode = first.departmentCode();
        String priority = first.priority();
        String riskLevel = first.riskLevel();

        for (TaskProposal p : group) {
            classification = higher(classification, p.classification());
            if (owner == null || owner.ambiguous()) {
                if (p.owner() != null && !p.owner().ambiguous()) {
                    owner = p.owner();
                }
            }
            if (dueDate == null) {
                dueDate = p.suggestedDueDate();
            } else if (p.suggestedDueDate() != null && p.suggestedDueDate().isBefore(dueDate)) {
                dueDate = p.suggestedDueDate();
            }
            if (p.collaborators() != null) {
                collaborators.addAll(p.collaborators());
            }
            if (p.evidence() != null) {
                evidence.addAll(p.evidence());
            }
            if (p.upstreamTaskRefs() != null) {
                upstreamRefs.addAll(p.upstreamTaskRefs());
            }
            if (p.reviewReasons() != null) {
                reviewReasons.addAll(p.reviewReasons());
            }
            needsReview = needsReview || p.needsReview();
            description = description == null ? p.description() : description;
            deliverable = deliverable == null ? p.deliverable() : deliverable;
            acceptanceCriteria = acceptanceCriteria == null ? p.acceptanceCriteria() : acceptanceCriteria;
            departmentCode = departmentCode == null ? p.departmentCode() : departmentCode;
            priority = priority == null ? p.priority() : priority;
            riskLevel = riskLevel == null ? p.riskLevel() : riskLevel;
        }

        return new TaskProposal(first.title(), description, classification, deliverable, acceptanceCriteria, owner,
            new ArrayList<>(collaborators), departmentCode, dueDate, priority, riskLevel,
            new ArrayList<>(evidence), new ArrayList<>(upstreamRefs), needsReview, new ArrayList<>(reviewReasons));
    }

    /** L1 最重、L3 最轻；null 视为最轻，保留更重的一侧。 */
    private static TaskClassification higher(TaskClassification a, TaskClassification b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
