package com.stonewu.agenteam.service.meeting;

/** 飞书多维表格字段元数据，用于绑定时的 schema 校验。 */
public record MeetingFieldMeta(String fieldId, String fieldName, String type, String uiType, boolean primary) {
}
