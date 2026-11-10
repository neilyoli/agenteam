package com.stonewu.agenteam.service.meeting;

/**
 * 确认轮次的技术索引状态。业务确认状态（待确认/已确认/需调整/已拒绝）以飞书为准，
 * 这里只记录本轮卡片是否已发出、是否已被回答，用于防重放与拒绝旧卡片。
 */
public enum MeetingConfirmationState {
    PENDING("pending"),
    CONFIRMED("confirmed"),
    NEEDS_REVISION("needs_revision"),
    REJECTED("rejected"),
    /** 负责人存在歧义或未绑定，未发送确认卡片。 */
    UNRESOLVED("unresolved");

    private final String value;

    MeetingConfirmationState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MeetingConfirmationState of(String value) {
        for (MeetingConfirmationState state : values()) {
            if (state.value.equals(value)) {
                return state;
            }
        }
        return null;
    }
}
