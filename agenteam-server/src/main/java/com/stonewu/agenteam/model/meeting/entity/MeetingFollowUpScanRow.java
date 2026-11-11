package com.stonewu.agenteam.model.meeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 会议督办扫描作业与结果；每次 Schedule 发生记录排入一个扫描作业，任务业务状态以飞书为准。 */
@Getter
@Setter
@TableName("meeting_follow_up_scan")
public class MeetingFollowUpScanRow implements EnterpriseScopedRecord {
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

    @TableField("schedule_id")
    private String scheduleId;

    @TableField("occurrence_id")
    private String occurrenceId;

    @TableField("policy_json")
    private String policyJson;

    @TableField("status")
    private String status;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("max_attempts")
    private Integer maxAttempts;

    @TableField("next_attempt_at")
    private Instant nextAttemptAt;

    @TableField("claim_token")
    private String claimToken;

    @TableField("lease_until")
    private Instant leaseUntil;

    @TableField("last_error_code")
    private String lastErrorCode;

    @TableField("result_json")
    private String resultJson;
}
