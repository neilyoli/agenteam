package com.stonewu.meeting.api;

/**
 * 飞书 Open API 调用失败的分类结果。错误分类决定宿主是否可安全重试；
 * 鉴权/权限/参数错误必须阻断并提示修复，超时与限流进入有界退避。
 */
public final class MeetingApiException extends RuntimeException {
    public enum Kind {
        AUTH_EXPIRED,
        PERMISSION_DENIED,
        NOT_FOUND,
        RATE_LIMITED,
        TIMEOUT,
        RETRYABLE,
        INVALID_ARGUMENT,
        UNKNOWN
    }

    private final Kind kind;

    public MeetingApiException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public MeetingApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return kind == Kind.RATE_LIMITED || kind == Kind.TIMEOUT || kind == Kind.RETRYABLE;
    }
}
