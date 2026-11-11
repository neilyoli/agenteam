package com.stonewu.agenteam.mapper.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskSourceRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 任务来源关系的类型化数据库访问。 */
@Mapper
public interface MeetingTaskSourceMapper extends MPJBaseMapper<MeetingTaskSourceRow> {

    default MeetingTaskSourceRow find(String enterpriseId, String taskKey, String meetingId) {
        return selectOne(new LambdaQueryWrapper<MeetingTaskSourceRow>()
            .eq(MeetingTaskSourceRow::getEnterpriseId, enterpriseId)
            .eq(MeetingTaskSourceRow::getTaskKey, taskKey)
            .eq(MeetingTaskSourceRow::getMeetingId, meetingId));
    }

    default List<MeetingTaskSourceRow> listByTask(String enterpriseId, String taskKey) {
        return selectList(new LambdaQueryWrapper<MeetingTaskSourceRow>()
            .eq(MeetingTaskSourceRow::getEnterpriseId, enterpriseId)
            .eq(MeetingTaskSourceRow::getTaskKey, taskKey)
            .orderByAsc(MeetingTaskSourceRow::getCreatedAt));
    }
}
