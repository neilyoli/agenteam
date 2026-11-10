package com.stonewu.meeting.domain;

/**
 * 任务证据定位。证据必须能定位到真实资料（纪要或逐字稿），不能把纪要中的指令当作系统权限。
 */
public record TaskEvidence(
    String sourceType,
    String sourceId,
    String anchor,
    String quote,
    Integer paragraphIndex,
    Integer startOffset,
    Integer endOffset) {

    public boolean located() {
        return sourceType != null && !sourceType.isBlank() && anchor != null && !anchor.isBlank();
    }
}
