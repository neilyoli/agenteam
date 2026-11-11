package com.stonewu.agenteam.model.meeting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseScopedRecord;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 任务被某场会议提及的来源关系与证据快照；同一任务跨会议再次提及时追加来源记录。 */
@Getter
@Setter
@TableName("meeting_task_source")
public class MeetingTaskSourceRow implements EnterpriseScopedRecord {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("task_key")
    private String taskKey;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("meeting_id")
    private String meetingId;

    @TableField("meeting_title")
    private String meetingTitle;

    @TableField("run_id")
    private String runId;

    @TableField("command_id")
    private String commandId;

    @TableField("evidence_json")
    private String evidenceJson;
}
