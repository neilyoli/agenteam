package com.stonewu.agenteam.model.meeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 系统到飞书的版本化投递箱记录；栅栏领取、幂等创建、字段级补写。 */
@Getter
@Setter
@TableName("meeting_sync_outbox")
public class MeetingSyncOutboxRow implements EnterpriseScopedRecord {
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

    @TableField("command_id")
    private String commandId;

    @TableField("desired_version")
    private Long desiredVersion;

    @TableField("delivered_version")
    private Long deliveredVersion;

    @TableField("record_id")
    private String recordId;

    @TableField("create_token")
    private String createToken;

    @TableField("claim_token")
    private String claimToken;

    @TableField("lease_until")
    private Instant leaseUntil;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("max_attempts")
    private Integer maxAttempts;

    @TableField("next_attempt_at")
    private Instant nextAttemptAt;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("last_error_code")
    private String lastErrorCode;

    @TableField("last_request_id")
    private String lastRequestId;

    @TableField("run_id")
    private String runId;

    @TableField("status")
    private String status;
}
