package com.stonewu.agenteam.service.meeting;

/** 会议同步的两类目标表：会议主表与会议督办宽表。 */
public enum MeetingBusinessType {
    MEETING("meeting"),
    TASK("task");

    private final String code;

    MeetingBusinessType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static MeetingBusinessType of(String code) {
        for (MeetingBusinessType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
