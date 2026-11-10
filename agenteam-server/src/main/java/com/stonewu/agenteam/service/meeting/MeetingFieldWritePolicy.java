package com.stonewu.agenteam.service.meeting;

/**
 * 业务字段的写入所有权。飞书是业务状态唯一事实来源：
 * 人工字段以飞书最新值为准，绝不进入系统投递 payload，避免后台重试回滚人工更新。
 */
public enum MeetingFieldWritePolicy {
    /** 分析提议字段，经受控命令写入；已确认任务的重要修改需重新确认（M5）。 */
    AI_PROPOSED,
    /** 人工业务字段，飞书为唯一事实来源，系统只读不写。 */
    MANUAL,
    /** 宿主管理的技术字段（身份、同步版本、命令关联）。 */
    TECHNICAL
}
