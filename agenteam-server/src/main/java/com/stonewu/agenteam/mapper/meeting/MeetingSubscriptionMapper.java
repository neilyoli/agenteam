package com.stonewu.agenteam.mapper.meeting;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import org.apache.ibatis.annotations.Mapper;

/** 会议事件订阅声明的类型化数据库访问。 */
@Mapper
public interface MeetingSubscriptionMapper extends MPJBaseMapper<MeetingSubscriptionRow> {
}
