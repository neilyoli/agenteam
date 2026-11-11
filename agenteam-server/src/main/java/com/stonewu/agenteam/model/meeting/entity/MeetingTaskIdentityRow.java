package com.stonewu.agenteam.model.meeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 会议任务稳定身份。宿主首次创建时分配并持久化，不随标题、负责人或描述变化；业务状态以飞书为准。 */
@Getter
@Setter
@TableName("meeting_task_identity")
public class MeetingTaskIdentityRow implements EnterpriseScopedRecord {
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

    @TableField("meeting_id")
    private String meetingId;

    @TableField("title_current")
    private String titleCurrent;

    @TableField("title_normalized")
    private String titleNormalized;

    @TableField("owner_open_id")
    private String ownerOpenId;

    @TableField("owner_user_id")
    private String ownerUserId;

    @TableField("owner_name")
    private String ownerName;

    @TableField("source_count")
    private Integer sourceCount;

    @TableField("status")
    private String status;

    @TableField("created_run_id")
    private String createdRunId;

    @TableField("created_command_id")
    private String createdCommandId;
}
