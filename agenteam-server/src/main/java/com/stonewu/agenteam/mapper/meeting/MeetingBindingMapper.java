package com.stonewu.agenteam.mapper.meeting;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import org.apache.ibatis.annotations.Mapper;

/** 会议飞书字段绑定的类型化数据库访问。 */
@Mapper
public interface MeetingBindingMapper extends MPJBaseMapper<MeetingBindingRow> {
}
