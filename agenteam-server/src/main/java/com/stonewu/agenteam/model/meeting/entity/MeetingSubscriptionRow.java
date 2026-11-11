package com.stonewu.agenteam.model.meeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 会议事件订阅声明；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("meeting_subscription")
public class MeetingSubscriptionRow implements EnterpriseScopedRecord {
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

    @TableField("event_type")
    private String eventType;

    @TableField("status")
    private String status;

    @TableField("hire_id")
    private String hireId;

    @TableField("agent_version_id")
    private String agentVersionId;

    @TableField("plugin_version")
    private String pluginVersion;

    @TableField("owner_user_id")
    private String ownerUserId;

    @TableField("config_json")
    private String configJson;
}
