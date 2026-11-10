package com.stonewu.meeting.service;

import com.stonewu.meeting.domain.OwnerCandidate;
import com.stonewu.meeting.domain.TaskClassification;
import com.stonewu.meeting.domain.TaskEvidence;
import com.stonewu.meeting.domain.TaskProposal;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskConsolidationServiceTest {
    private final TaskKeyService keys = new TaskKeyService();
    private final TaskConsolidationService consolidation = new TaskConsolidationService();

    @Test
    void stableKeyDependsOnNormalizedTitleAndOwner() {
        String a = keys.compute("  统一 对账口径 ", "ou_1");
        String b = keys.compute("统一 对账口径", "ou_1");
        assertEquals(a, b);
        assertNotEquals(a, keys.compute("统一 对账口径", "ou_2"));
        assertNotEquals(a, keys.compute("另一事项", "ou_1"));
        assertTrue(a.startsWith("T-"));
    }

    @Test
    void deduplicatesSameTaskAndMergesEvidenceAndUpstream() {
        var p1 = proposal("统一对账口径", "ou_1", TaskClassification.L2, LocalDate.of(2026, 11, 30),
            List.of(evidence("minutes", "ob_1", "minutes#3")), List.of("UP-1"));
        var p2 = proposal("统一对账口径", "ou_1", TaskClassification.L2, LocalDate.of(2026, 11, 30),
            List.of(evidence("minutes", "ob_1", "minutes#7")), List.of("UP-2"));
        var result = consolidation.consolidate(List.of(p1, p2), List.of());

        assertEquals(1, result.tasks().size());
        var merged = result.tasks().get(0).proposal();
        assertEquals(2, merged.evidence().size());
        assertEquals(List.of("UP-1", "UP-2"), merged.upstreamTaskRefs());
    }

    @Test
    void keepsHigherClassificationOnMerge() {
        var l2 = proposal("事项", "ou_1", TaskClassification.L2, null, List.of(), List.of());
        var l1 = proposal("事项", "ou_1", TaskClassification.L1, null, List.of(), List.of());
        assertEquals(TaskClassification.L1,
            consolidation.consolidate(List.of(l2, l1), List.of()).tasks().get(0).proposal().classification());
    }

    @Test
    void keepsEarliestDueDateOnMerge() {
        var later = proposal("事项", "ou_1", TaskClassification.L2, LocalDate.of(2026, 12, 1), List.of(), List.of());
        var earlier = proposal("事项", "ou_1", TaskClassification.L2, LocalDate.of(2026, 11, 1), List.of(), List.of());
        assertEquals(LocalDate.of(2026, 11, 1),
            consolidation.consolidate(List.of(later, earlier), List.of()).tasks().get(0).proposal().suggestedDueDate());
    }

    @Test
    void reportsCoveredAndUncoveredUpstream() {
        var task = proposal("下游覆盖", "ou_1", TaskClassification.L2, null, List.of(), List.of("UP-1", "UP-2"));
        var result = consolidation.consolidate(List.of(task), List.of("UP-1", "UP-2", "UP-3"));
        assertEquals(List.of("UP-1", "UP-2"), result.coveredUpstream());
        assertEquals(List.of("UP-3"), result.uncoveredUpstream());
    }

    private static TaskProposal proposal(String title, String ownerOpenId, TaskClassification classification,
                                         LocalDate dueDate, List<TaskEvidence> evidence, List<String> upstreamRefs) {
        return new TaskProposal(title, null, classification, null, null,
            OwnerCandidate.resolved(ownerOpenId, null, "张三"), List.of(), null, dueDate, null, null,
            evidence, upstreamRefs, false, List.of());
    }

    private static TaskEvidence evidence(String sourceType, String sourceId, String anchor) {
        return new TaskEvidence(sourceType, sourceId, anchor, "原文摘录", null, null, null);
    }
}
