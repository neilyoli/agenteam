package com.stonewu.agenteam.service.meeting;

/**
 * 任务业务状态（飞书「任务状态」单选字段的唯一事实来源）。
 *
 * <p>确认闭环（M5）与持续督办（M6）共用这一条线性状态机：
 * 待确认 → 进行中 → 已完成，负向分支为需调整 / 已拒绝。
 * 合并了原先的「确认状态」与「执行状态」两个字段，字段值以飞书为唯一事实来源。</p>
 */
public enum MeetingTaskStatus {
    PENDING("待确认"),
    IN_PROGRESS("进行中"),
    COMPLETED("已完成"),
    NEEDS_REVISION("需调整"),
    REJECTED("已拒绝");

    private final String label;

    MeetingTaskStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 飞书单选值反查状态；未匹配返回 null。 */
    public static MeetingTaskStatus of(String label) {
        for (MeetingTaskStatus status : values()) {
            if (status.label.equals(label)) {
                return status;
            }
        }
        return null;
    }

    /** 是否已完成（M6 逾期督办据此跳过，不再催办）。 */
    public static boolean isDone(String raw) {
        return COMPLETED.label.equals(raw) || "completed".equalsIgnoreCase(raw) || "done".equalsIgnoreCase(raw);
    }

    /** 是否未确认（空或「待确认」；M6 未确认督办据此触发）。 */
    public static boolean isUnconfirmed(String raw) {
        return raw == null || raw.isBlank() || PENDING.label.equals(raw);
    }
}
