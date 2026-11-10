package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一次扫描识别出的待跟进任务候选；evidence 携带来源证据（标题/截止日期/证据定位/未覆盖上游等）。
 */
public record MeetingFollowUpCandidate(String businessKey, String title, String ownerSubject,
                                       List<String> reasons, JsonNode evidence) {
}
