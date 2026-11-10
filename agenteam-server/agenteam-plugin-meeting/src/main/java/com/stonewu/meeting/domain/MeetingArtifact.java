package com.stonewu.meeting.domain;

/**
 * 会议资料（智能纪要 / Minutes / 逐字稿）的就绪状态。资料未就绪时由宿主持久化等待条件，
 * 不占用插件进程；就绪后可恢复。
 */
public record MeetingArtifact(
    String minuteToken,
    boolean minutesReady,
    boolean transcriptReady,
    boolean noteReady,
    String noteUrl,
    Integer minutesWordCount,
    Integer transcriptWordCount) {

    /** 是否存在任何可用于分析的资料。 */
    public boolean anyReady() {
        return minutesReady || transcriptReady || noteReady;
    }
}
