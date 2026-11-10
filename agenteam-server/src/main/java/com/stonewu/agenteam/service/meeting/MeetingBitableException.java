package com.stonewu.agenteam.service.meeting;

/**
 * 飞书多维表格调用失败。retryable 区分暂时性失败（退避重试）与
 * 鉴权/Schema/权限等需人工修复的阻断，异常文本不含密钥与原始响应正文。
 */
public class MeetingBitableException extends RuntimeException {
    private final String feishuCode;
    private final String requestId;
    private final boolean retryable;

    public MeetingBitableException(String message, String feishuCode, String requestId, boolean retryable) {
        super(message);
        this.feishuCode = feishuCode;
        this.requestId = requestId;
        this.retryable = retryable;
    }

    public MeetingBitableException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.feishuCode = null;
        this.requestId = null;
        this.retryable = retryable;
    }

    public String feishuCode() {
        return feishuCode;
    }

    public String requestId() {
        return requestId;
    }

    public boolean retryable() {
        return retryable;
    }
}
