package com.stonewu.agenteam.model.meeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 企业统一飞书 Base 的字段绑定；数据库记录不直接作为接口响应。 */
@Getter
@Setter
@TableName("meeting_binding")
public class MeetingBindingRow implements EnterpriseScopedRecord {
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

    @TableField("name")
    private String name;

    @TableField("app_token")
    private String appToken;

    @TableField("table_id")
    private String tableId;

    @TableField("business_type")
    private String businessType;

    @TableField("key_field")
    private String keyField;

    @TableField("field_mapping_json")
    private String fieldMappingJson;

    @TableField("writeback_json")
    private String writebackJson;

    @TableField("status")
    private String status;

    @TableField("schema_revision")
    private String schemaRevision;

    @TableField("owner_user_id")
    private String ownerUserId;

    @TableField("config_json")
    private String configJson;
}
