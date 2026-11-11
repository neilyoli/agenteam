package com.stonewu.meeting.domain;

import java.util.List;

/**
 * 负责人解析候选。存在歧义时不自动指派、不发送冒名确认卡片；必须由人复核。
 */
public record OwnerCandidate(
    String resolvedOpenId,
    String resolvedUserId,
    String displayName,
    boolean ambiguous,
    List<String> reasons) {

    public static OwnerCandidate ambiguous(String displayName, List<String> reasons) {
        return new OwnerCandidate(null, null, displayName, true, List.copyOf(reasons));
    }

    public static OwnerCandidate resolved(String openId, String userId, String displayName) {
        return new OwnerCandidate(openId, userId, displayName, false, List.of());
    }
}
