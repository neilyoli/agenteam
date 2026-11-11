package com.stonewu.agenteam.mapper.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

/**
 * 催办台账的数据库语句；remind 以 next_reminder_at 作为限频栅栏原子抢占，recordSent 仅在催办成功后回填。
 */
@Mapper
public interface MeetingFollowUpMapper extends MPJBaseMapper<MeetingFollowUpRow> {

    int remind(@Param("enterpriseId") String enterpriseId, @Param("bindingId") String bindingId,
               @Param("businessKey") String businessKey, @Param("now") Timestamp now,
               @Param("nextReminderAt") Timestamp nextReminderAt, @Param("evidenceJson") String evidenceJson);

    int recordSent(@Param("enterpriseId") String enterpriseId, @Param("bindingId") String bindingId,
                   @Param("businessKey") String businessKey, @Param("now") Timestamp now);

    default MeetingFollowUpRow find(String enterpriseId, String bindingId, String businessKey) {
        return selectOne(new LambdaQueryWrapper<MeetingFollowUpRow>()
            .eq(MeetingFollowUpRow::getEnterpriseId, enterpriseId)
            .eq(MeetingFollowUpRow::getBindingId, bindingId)
            .eq(MeetingFollowUpRow::getBusinessKey, businessKey));
    }
}
