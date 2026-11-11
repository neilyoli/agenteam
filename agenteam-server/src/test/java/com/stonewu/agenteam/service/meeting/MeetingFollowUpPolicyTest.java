package com.stonewu.agenteam.service.meeting;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 督办策略的工作时段（Asia/Shanghai）、提醒频率与跟进条件判定。 */
class MeetingFollowUpPolicyTest {

    @Test
    void workingWindowUsesShanghaiHours() {
        var policy = new MeetingFollowUpPolicy("binding", 1440, 9, 18,
            Set.of(MeetingFollowUpPolicy.UNCONFIRMED));
        // 01:00Z = 09:00 上海，落在 [9,18) 内
        assertTrue(policy.withinWindow(Instant.parse("2026-01-01T01:00:00Z")));
        // 09:59Z = 17:59 上海，仍在窗口内
        assertTrue(policy.withinWindow(Instant.parse("2026-01-01T09:59:00Z")));
        // 10:00Z = 18:00 上海，窗口右开
        assertFalse(policy.withinWindow(Instant.parse("2026-01-01T10:00:00Z")));
        // 00:00Z = 08:00 上海，早于窗口
        assertFalse(policy.withinWindow(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void cooldownAndConditionsExposeConfiguredValues() {
        var policy = new MeetingFollowUpPolicy("binding", 60, 9, 18,
            Set.of(MeetingFollowUpPolicy.OVERDUE, MeetingFollowUpPolicy.UPSTREAM_UNCOVERED));
        assertEquals(Duration.ofMinutes(60), policy.cooldown());
        assertTrue(policy.covers(MeetingFollowUpPolicy.OVERDUE));
        assertFalse(policy.covers(MeetingFollowUpPolicy.UNCONFIRMED));
    }
}
