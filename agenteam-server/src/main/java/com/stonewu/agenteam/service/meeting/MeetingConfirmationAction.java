package com.stonewu.agenteam.service.meeting;

/** 确认卡片上的负责人操作，映射到确认轮次的最终技术状态与飞书任务状态。 */
public enum MeetingConfirmationAction {
    CONFIRM("confirm", MeetingConfirmationState.CONFIRMED, MeetingTaskStatus.IN_PROGRESS),
    REVISE("revise", MeetingConfirmationState.NEEDS_REVISION, MeetingTaskStatus.NEEDS_REVISION),
    REJECT("reject", MeetingConfirmationState.REJECTED, MeetingTaskStatus.REJECTED);

    private final String value;
    private final MeetingConfirmationState state;
    private final MeetingTaskStatus taskStatus;

    MeetingConfirmationAction(String value, MeetingConfirmationState state, MeetingTaskStatus taskStatus) {
        this.value = value;
        this.state = state;
        this.taskStatus = taskStatus;
    }

    public String value() {
        return value;
    }

    public MeetingConfirmationState state() {
        return state;
    }

    /** 负责人操作落到飞书「任务状态」的业务值。 */
    public MeetingTaskStatus taskStatus() {
        return taskStatus;
    }

    public static MeetingConfirmationAction of(String value) {
        for (MeetingConfirmationAction action : values()) {
            if (action.value.equals(value)) {
                return action;
            }
        }
        return null;
    }
}
