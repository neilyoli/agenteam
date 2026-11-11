package com.stonewu.agenteam.model.meeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 会议任务确认的版本与身份技术索引；确认状态以飞书任务宽表为唯一事实来源。 */
@Getter
@Setter
@TableName("meeting_confirmation")
public class MeetingConfirmationRow implements EnterpriseScopedRecord {
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

    @TableField("binding_id")
    private String bindingId;

    @TableField("business_key")
    private String businessKey;

    @TableField("task_version")
    private Long taskVersion;

    @TableField("owner_user_id")
    private String ownerUserId;

    @TableField("owner_subject")
    private String ownerSubject;

    @TableField("status")
    private String status;

    @TableField("confirm_comment")
    private String confirmComment;

    @TableField("card_message_id")
    private String cardMessageId;

    @TableField("card_command_id")
    private String cardCommandId;

    @TableField("card_status")
    private String cardStatus;

    @TableField("card_title")
    private String cardTitle;

    @TableField("card_description")
    private String cardDescription;

    @TableField("card_attempt_count")
    private Integer cardAttemptCount;

    @TableField("card_max_attempts")
    private Integer cardMaxAttempts;

    @TableField("card_next_attempt_at")
    private Instant cardNextAttemptAt;

    @TableField("card_claim_token")
    private String cardClaimToken;

    @TableField("card_lease_until")
    private Instant cardLeaseUntil;

    @TableField("card_last_error")
    private String cardLastError;

    @TableField("last_event_id")
    private String lastEventId;

    @TableField("run_id")
    private String runId;

    @TableField("config_json")
    private String configJson;
}
