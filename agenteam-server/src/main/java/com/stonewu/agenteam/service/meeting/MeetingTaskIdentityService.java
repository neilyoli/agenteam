package com.stonewu.agenteam.service.meeting;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 任务稳定身份的分配入口，宿主在发布事务内调用。M7-B 前的过渡实现沿用
 * “规范化标题 + 负责人 openId”的内容哈希；M7-B 将替换为宿主首次创建时
 * 持久化分配、跨会议保持不变的稳定身份，调用方契约不变。
 */
@Service
public class MeetingTaskIdentityService {

    /** 分配任务身份键；同一逻辑任务在本阶段得到相同键，用于投递箱幂等 upsert。 */
    public String assignKey(String title, String ownerOpenId) {
        String normalized = normalize(title);
        String identity = ownerOpenId == null || ownerOpenId.isBlank()
            ? normalized : normalized + '\n' + ownerOpenId.strip();
        return "T-" + sha256Hex(identity).substring(0, 32);
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
