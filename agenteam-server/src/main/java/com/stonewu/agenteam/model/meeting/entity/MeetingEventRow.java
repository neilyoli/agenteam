package com.stonewu.agenteam.model.meeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 会议事件收件箱记录；技术状态与关联 Run，不保存纪要正文。 */
@Getter
@Setter
@TableName("meeting_event")
public class MeetingEventRow implements EnterpriseScopedRecord {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("integration_id")
    private String integrationId;

    @TableField("event_id")
    private String eventId;

    @TableField("event_type")
    private String eventType;

    @TableField("meeting_id")
    private String meetingId;

    @TableField("minute_token")
    private String minuteToken;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("status")
    private String status;

    @TableField("reason_code")
    private String reasonCode;

    @TableField("run_id")
    private String runId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("job_id")
    private String jobId;

    @TableField("error_code")
    private String errorCode;

    @TableField("wait_until")
    private Instant waitUntil;
}
