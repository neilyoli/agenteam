package com.stonewu.agenteam.model.meeting.response;

/**
 * 命令入队结果；business_key 为稳定业务唯一键值，command_id 用于对账。
 */
public record MeetingSyncCommandResult(String bindingId, String businessKey, String commandId) {
}
