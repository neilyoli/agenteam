package com.stonewu.agenteam.mapper.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingConfirmationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

/**
 * 确认轮次的持久化；回答用带版本与状态栅栏的原子更新，卡片投递后回填平台消息编号。
 */
@Mapper
public interface MeetingConfirmationMapper extends MPJBaseMapper<MeetingConfirmationRow> {

    /** 仅当仍为待确认且版本一致时回答，防止旧卡片或重复回调覆盖新状态。 */
    int answer(@Param("enterpriseId") String enterpriseId, @Param("bindingId") String bindingId,
               @Param("businessKey") String businessKey, @Param("expectedVersion") long expectedVersion,
               @Param("toStatus") String toStatus, @Param("comment") String comment,
               @Param("eventId") String eventId, @Param("now") Timestamp now);

    /** 卡片投递成功后回填平台消息编号与投递命令编号。 */
    int recordCard(@Param("enterpriseId") String enterpriseId, @Param("bindingId") String bindingId,
                   @Param("businessKey") String businessKey, @Param("messageId") String messageId,
                   @Param("commandId") String commandId, @Param("now") Timestamp now);

    /** 开始新一轮确认：重置版本、负责人与状态，清空上一轮的意见、卡片与回调记录。 */
    int beginRound(@Param("enterpriseId") String enterpriseId, @Param("bindingId") String bindingId,
                   @Param("businessKey") String businessKey, @Param("taskVersion") long taskVersion,
                   @Param("ownerUserId") String ownerUserId, @Param("ownerSubject") String ownerSubject,
                   @Param("status") String status, @Param("now") Timestamp now);

    default MeetingConfirmationRow find(String enterpriseId, String bindingId, String businessKey) {
        return selectOne(new LambdaQueryWrapper<MeetingConfirmationRow>()
            .eq(MeetingConfirmationRow::getEnterpriseId, enterpriseId)
            .eq(MeetingConfirmationRow::getBindingId, bindingId)
            .eq(MeetingConfirmationRow::getBusinessKey, businessKey));
    }
}
