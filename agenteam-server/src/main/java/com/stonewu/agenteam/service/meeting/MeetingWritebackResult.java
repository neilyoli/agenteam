package com.stonewu.agenteam.service.meeting;

/** 投递成功后的回写结果键，用于在飞书记录上标注同步状态、版本与时间。 */
public enum MeetingWritebackResult {
    SYNC_STATUS("sync_status"),
    SYNC_VERSION("sync_version"),
    SYNC_TIME("sync_time");

    private final String code;

    MeetingWritebackResult(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static MeetingWritebackResult of(String code) {
        for (MeetingWritebackResult value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
