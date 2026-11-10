package com.stonewu.agenteam.model.meeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 会议任务催办台账；只做限频去重与证据快照，任务业务状态以飞书为唯一事实来源。 */
@Getter
@Setter
@TableName("meeting_follow_up")
public class MeetingFollowUpRow implements EnterpriseScopedRecord {
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

    @TableField("owner_user_id")
    private String ownerUserId;

    @TableField("owner_subject")
    private String ownerSubject;

    @TableField("last_reminder_at")
    private Instant lastReminderAt;

    @TableField("next_reminder_at")
    private Instant nextReminderAt;

    @TableField("reminder_count")
    private Integer reminderCount;

    @TableField("last_evidence_json")
    private String lastEvidenceJson;

    @TableField("status")
    private String status;
}
