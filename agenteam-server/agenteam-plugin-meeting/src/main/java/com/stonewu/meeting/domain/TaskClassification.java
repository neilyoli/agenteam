package com.stonewu.meeting.domain;

/**
 * 会议任务分类：L1 经营承诺、L2 跨部门交付事项必须进入督办；L3 部门内部日常执行不进入督办。
 * 分类由 Agent 提议，结构与可确定条件由 Validator 强制校验，语义不确定项保留复核标记。
 */
public enum TaskClassification {
    L1,
    L2,
    L3;

    /** 是否进入督办宽表。 */
    public boolean entersSupervision() {
        return this == L1 || this == L2;
    }

    public static TaskClassification fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return switch (code.trim().toUpperCase()) {
            case "L1" -> L1;
            case "L2" -> L2;
            case "L3" -> L3;
            default -> null;
        };
    }
}
