package com.stonewu.meeting;

/**
 * 插件业务失败。错误码供模型与调用方判断；消息面向用户，不包含凭据或完整敏感逐字稿。
 */
public final class MeetingError extends RuntimeException {
    private final String code;

    public MeetingError(String code, String message) {
        super(message);
        this.code = code;
    }

    public MeetingError(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static MeetingError invalidArgument(String message) {
        return new MeetingError("INVALID_ARGUMENT", message);
    }

    public static MeetingError unavailable(String message) {
        return new MeetingError("ARTIFACT_NOT_READY", message);
    }
}
