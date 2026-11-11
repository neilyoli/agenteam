package com.stonewu.agenteam.service.meeting;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

/**
 * 督办扫描策略的固化快照：绑定、提醒频率、工作时段与跟进条件。
 * 工作时段以 Asia/Shanghai 判定；真实租户时区需在 M7 用飞书部署配置校准。
 */
public record MeetingFollowUpPolicy(String bindingId, long cooldownMinutes, int windowStartHour,
                                    int windowEndHour, Set<String> conditions) {
    public static final String UNCONFIRMED = "unconfirmed";
    public static final String OVERDUE = "overdue";
    public static final String UPSTREAM_UNCOVERED = "upstream_uncovered";
    public static final Set<String> SUPPORTED_CONDITIONS = Set.of(UNCONFIRMED, OVERDUE, UPSTREAM_UNCOVERED);

    private static final ZoneId WORKING_ZONE = ZoneId.of("Asia/Shanghai");

    /** 是否处于工作时段；不在工作时段则本次扫描不催办，下一发生记录再评估。 */
    public boolean withinWindow(Instant now) {
        int hour = now.atZone(WORKING_ZONE).getHour();
        return hour >= windowStartHour && hour < windowEndHour;
    }

    /** 同一任务两次催办之间的最小间隔。 */
    public Duration cooldown() {
        return Duration.ofMinutes(cooldownMinutes);
    }

    public boolean covers(String condition) {
        return conditions.contains(condition);
    }
}
