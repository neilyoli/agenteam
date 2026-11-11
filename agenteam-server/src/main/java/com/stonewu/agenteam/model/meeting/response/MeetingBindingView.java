package com.stonewu.agenteam.model.meeting.response;

import java.util.Map;

/** 字段绑定的公开视图，不包含凭据。 */
public record MeetingBindingView(String id, String enterpriseId, String integrationId, String name,
                                 String appToken, String tableId, String businessType, String keyField,
                                 Map<String, String> fieldMapping, Map<String, String> writeback, String status,
                                 String schemaRevision, String ownerUserId, long revision, String createdAt,
                                 String updatedAt) {
}
