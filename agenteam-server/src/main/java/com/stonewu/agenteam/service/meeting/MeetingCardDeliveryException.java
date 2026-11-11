package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.service.integration.ChannelProviderException;

/**
 * 确认卡片投递的结果分类。UNKNOWN 表示发送结果不明（网络超时等，消息可能已发出），
 * uuid 幂等窗口不能作为可靠去重依据，必须转人工核查而不能自动重发；
 * REJECTED 表示飞书明确拒绝（消息必然未送达），可按错误类别安全重试或阻断。
 */
public class MeetingCardDeliveryException extends ChannelProviderException {
    public enum Outcome { UNKNOWN, REJECTED }

    public static final String UNKNOWN_CODE = "MEETING_CARD_UNKNOWN";
    public static final String REJECTED_CODE = "MEETING_CARD_REJECTED";

    private final Outcome outcome;
    private final boolean retryable;

    public MeetingCardDeliveryException(Outcome outcome, boolean retryable, String summary, Throwable cause) {
        super(outcome == Outcome.UNKNOWN ? UNKNOWN_CODE : REJECTED_CODE, summary, cause);
        this.outcome = outcome;
        this.retryable = retryable;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** 仅 REJECTED 结果区分可重试（限流等暂时性拒绝）与阻断（参数或权限错误）。 */
    public boolean retryable() {
        return retryable;
    }
}
