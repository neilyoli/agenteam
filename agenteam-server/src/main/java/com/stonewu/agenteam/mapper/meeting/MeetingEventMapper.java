package com.stonewu.agenteam.mapper.meeting;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRow;
import org.apache.ibatis.annotations.Mapper;

/** 会议事件收件箱的类型化数据库访问。 */
@Mapper
public interface MeetingEventMapper extends MPJBaseMapper<MeetingEventRow> {
}
