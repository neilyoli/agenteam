package com.stonewu.agenteam.service.meeting;

/** 确认卡片上的负责人操作，映射到确认轮次的最终状态。 */
public enum MeetingConfirmationAction {
    CONFIRM("confirm", MeetingConfirmationState.CONFIRMED),
    REVISE("revise", MeetingConfirmationState.NEEDS_REVISION),
    REJECT("reject", MeetingConfirmationState.REJECTED);

    private final String value;
    private final MeetingConfirmationState state;

    MeetingConfirmationAction(String value, MeetingConfirmationState state) {
        this.value = value;
        this.state = state;
    }

    public String value() {
        return value;
    }

    public MeetingConfirmationState state() {
        return state;
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
