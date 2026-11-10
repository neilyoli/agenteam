package com.stonewu.meeting.service;

import com.stonewu.meeting.domain.TaskClassification;
import com.stonewu.meeting.domain.TaskEvidence;
import com.stonewu.meeting.domain.TaskProposal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务候选的业务约束校验器。语义判断交给 Agent，此处只守住可确定的硬性约束：
 * 进入督办（L1/L2）的任务必须有已解析的负责人、可定位的证据与建议截止日期。
 * 结构缺失返回失败与原因；语义不确定项标记 needsReview，交由人工复核而非自动放行。
 */
public final class MeetingResultValidator {

    public record ValidationResult(boolean valid, List<String> violations, boolean needsReview) {
        public ValidationResult {
            violations = List.copyOf(violations);
        }
    }

    public ValidationResult validate(TaskProposal proposal) {
        List<String> violations = new ArrayList<>();
        boolean needsReview = proposal == null || proposal.needsReview()
            || (proposal.reviewReasons() != null && !proposal.reviewReasons().isEmpty());

        if (proposal == null) {
            return new ValidationResult(false, List.of("缺少任务候选"), needsReview);
        }

        if (blank(proposal.title())) {
            violations.add("任务标题不能为空");
        }
        TaskClassification classification = proposal.classification();
        if (classification == null) {
            violations.add("任务分类必须为 L1、L2 或 L3");
        } else if (classification.entersSupervision()) {
            // L1/L2 进入督办：负责人、证据、截止日期为硬性约束。
            if (proposal.owner() == null || proposal.owner().ambiguous()) {
                violations.add("督办任务必须有已确认的负责人");
            }
            boolean located = proposal.evidence() != null
                && proposal.evidence().stream().anyMatch(TaskEvidence::located);
            if (!located) {
                violations.add("督办任务必须包含可定位到纪要或逐字稿的证据");
            }
            if (proposal.suggestedDueDate() == null) {
                violations.add("督办任务必须有建议截止日期");
            }
        }

        if (proposal.suggestedDueDate() != null
            && proposal.suggestedDueDate().isBefore(LocalDate.now())) {
            needsReview = true;
        }

        return new ValidationResult(violations.isEmpty(), violations, needsReview);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
