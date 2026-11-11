package com.stonewu.agenteam.mapper.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskIdentityRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 会议任务稳定身份的类型化数据库访问。 */
@Mapper
public interface MeetingTaskIdentityMapper extends MPJBaseMapper<MeetingTaskIdentityRow> {

    default MeetingTaskIdentityRow find(String enterpriseId, String taskKey) {
        return selectOne(new LambdaQueryWrapper<MeetingTaskIdentityRow>()
            .eq(MeetingTaskIdentityRow::getEnterpriseId, enterpriseId)
            .eq(MeetingTaskIdentityRow::getId, taskKey));
    }

    /** 同一规范化标题与负责人的活动任务；用于发布前的重复候选提示，不做自动合并。 */
    default List<MeetingTaskIdentityRow> findActiveByTitleAndOwner(String enterpriseId, String normalizedTitle,
                                                                   String ownerOpenId) {
        return selectList(new LambdaQueryWrapper<MeetingTaskIdentityRow>()
            .eq(MeetingTaskIdentityRow::getEnterpriseId, enterpriseId)
            .eq(MeetingTaskIdentityRow::getStatus, "active")
            .eq(MeetingTaskIdentityRow::getTitleNormalized, normalizedTitle)
            .eq(ownerOpenId != null, MeetingTaskIdentityRow::getOwnerOpenId, ownerOpenId)
            .orderByAsc(MeetingTaskIdentityRow::getId));
    }
}
