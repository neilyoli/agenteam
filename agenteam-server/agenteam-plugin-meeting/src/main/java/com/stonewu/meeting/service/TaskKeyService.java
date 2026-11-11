package com.stonewu.meeting.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 稳定任务标识：由规范化标题 + 已解析负责人 openId 计算内容哈希。
 * 跨会议、跨运行对同一任务的重复提及得到相同 key，用于去重与幂等 upsert；
 * 不依赖会议编号或随机值，负责人未解析时退化为仅按标题，仍保持确定但需人工复核。
 */
public final class TaskKeyService {
    private static final int HEX_LENGTH = 32;

    public String compute(String title, String ownerOpenId) {
        String normalized = normalize(title);
        String identity = ownerOpenId == null || ownerOpenId.isBlank()
            ? normalized : normalized + '\n' + ownerOpenId.strip();
        return "T-" + sha256Hex(identity).substring(0, HEX_LENGTH);
    }

    /** 标题规范化：去首尾空白、折叠空白、小写；用于消除等价写法差异。 */
    static String normalize(String title) {
        if (title == null) {
            return "";
        }
        return title.strip().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前运行环境缺少 SHA-256", impossible);
        }
    }
}
