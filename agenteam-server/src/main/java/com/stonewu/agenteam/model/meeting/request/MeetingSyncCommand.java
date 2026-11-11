package com.stonewu.agenteam.model.meeting.request;

import java.util.Map;

/**
 * 受控宿主命令：分析完成后向指定 Binding 提交业务字段快照。
 * 字段键为业务字段编码，值类型由业务字段语义决定；人工字段与未映射字段在命令入口被拒绝。
 */
public record MeetingSyncCommand(
    String bindingId,
    Map<String, Object> fields,
    String commandId,
    String runId) {
}
