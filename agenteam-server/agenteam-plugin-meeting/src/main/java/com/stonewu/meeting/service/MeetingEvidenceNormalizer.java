package com.stonewu.meeting.service;

import com.stonewu.meeting.MeetingError;
import com.stonewu.meeting.domain.TaskEvidence;

import java.util.Locale;
import java.util.Set;

/**
 * 证据规范化：把 Agent 提出的证据引用整理为可定位锚点。
 * 来源只允许纪要（minutes）或逐字稿（transcript）；锚点优先使用段落/语句序号，
 * 引用文本截断脱敏，偏移量在可用时回填，无法定位的证据直接判为无效。
 */
public final class MeetingEvidenceNormalizer {
    private static final Set<String> SOURCE_TYPES = Set.of("minutes", "transcript");
    private static final int MAX_QUOTE_LENGTH = 2000;

    public TaskEvidence normalize(String sourceType, String sourceId, String anchor,
                                  String quote, Integer paragraphIndex,
                                  Integer startOffset, Integer endOffset) {
        String type = sourceType == null ? "" : sourceType.strip().toLowerCase(Locale.ROOT);
        if (!SOURCE_TYPES.contains(type)) {
            throw MeetingError.invalidArgument("证据来源只能为 minutes 或 transcript");
        }
        String resolvedAnchor = notBlank(anchor) ? anchor.strip()
            : (paragraphIndex != null ? type + "#" + paragraphIndex : null);
        String quoteTrimmed = quote == null ? null : quote.strip();
        if (!notBlank(quoteTrimmed) && !notBlank(resolvedAnchor)) {
            throw MeetingError.invalidArgument("证据必须包含引用文本或可定位锚点");
        }
        return new TaskEvidence(
            type,
            sourceId,
            resolvedAnchor,
            truncate(quoteTrimmed, MAX_QUOTE_LENGTH),
            paragraphIndex,
            startOffset,
            endOffset);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
