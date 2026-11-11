package com.stonewu.agenteam.mapper.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingConfirmationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 确认轮次的持久化与企业锁；回答用版本与状态栅栏的原子更新表达，
 * 卡片投递用独立租约与退避状态机，失败不回滚任务同步。
 */
@Mapper
public interface MeetingConfirmationMapper extends MPJBaseMapper<MeetingConfirmationRow> {

    /** 仅当仍为待确认且版本一致时回答，防止旧卡片或重复回调覆盖。 */
    int answer(@Param("enterpriseId") String enterpriseId, @Param("bindingId") String bindingId,
               @Param("businessKey") String businessKey, @Param("expectedVersion") long expectedVersion,
               @Param("toStatus") String toStatus, @Param("comment") String comment,
               @Param("eventId") String eventId, @Param("now") Timestamp now);

    /** 卡片投递成功后回填平台消息编号与投递命令编号。 */
    int recordCard(@Param("enterpriseId") String enterpriseId, @Param("bindingId") String bindingId,
                   @Param("businessKey") String businessKey, @Param("messageId") String messageId,
                   @Param("commandId") String commandId, @Param("now") Timestamp now);

    /** 开始新一轮确认：重置版本、负责人、状态与整组卡片投递状态，稳定命令编号按版本生成。 */
    int beginRound(@Param("enterpriseId") String enterpriseId, @Param("bindingId") String bindingId,
                   @Param("businessKey") String businessKey, @Param("taskVersion") long taskVersion,
                   @Param("ownerUserId") String ownerUserId, @Param("ownerSubject") String ownerSubject,
                   @Param("status") String status, @Param("cardStatus") String cardStatus,
                   @Param("cardTitle") String cardTitle, @Param("cardDescription") String cardDescription,
                   @Param("cardCommandId") String cardCommandId, @Param("now") Timestamp now);

    /** 对账扫描：任务投递箱已确认 delivered，但确认轮次缺失或落后于已投递版本。 */
    List<MeetingSyncOutboxRow> deliveredTasksAwaitingConfirmation(@Param("limit") int limit);

    /** 原子领取到期的卡片投递租约；过期持有者因 token 不匹配无法回写。 */
    int claimCardSend(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
                      @Param("token") String token, @Param("leaseUntil") Timestamp leaseUntil,
                      @Param("now") Timestamp now);

    /** 卡片投递成功：回填消息编号并完成本轮投递。 */
    int completeCardSend(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
                         @Param("token") String token, @Param("messageId") String messageId,
                         @Param("now") Timestamp now);

    /** 卡片投递明确失败：计数退避重试，超过上限阻断。 */
    int failCardSend(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
                     @Param("token") String token, @Param("nextAttemptAt") Timestamp nextAttemptAt,
                     @Param("status") String status, @Param("error") String error, @Param("now") Timestamp now);

    /** 卡片投递结果未知：停止自动重发（uuid 幂等窗口不可靠），转人工核查。 */
    int unknownCardSend(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
                        @Param("token") String token, @Param("error") String error, @Param("now") Timestamp now);

    default MeetingConfirmationRow find(String enterpriseId, String bindingId, String businessKey) {
        return selectOne(new LambdaQueryWrapper<MeetingConfirmationRow>()
            .eq(MeetingConfirmationRow::getEnterpriseId, enterpriseId)
            .eq(MeetingConfirmationRow::getBindingId, bindingId)
            .eq(MeetingConfirmationRow::getBusinessKey, businessKey));
    }

    /** 到期可投递的卡片（全部企业）：仍待确认且卡片状态为待发送或可重试失败。 */
    default List<MeetingConfirmationRow> dueCardSends(Instant now, int limit) {
        return selectList(new LambdaQueryWrapper<MeetingConfirmationRow>()
            .eq(MeetingConfirmationRow::getStatus, "pending")
            .in(MeetingConfirmationRow::getCardStatus, "pending", "failed")
            .le(MeetingConfirmationRow::getCardNextAttemptAt, Timestamp.from(now))
            .and(wrapper -> wrapper.isNull(MeetingConfirmationRow::getCardClaimToken)
                .or().le(MeetingConfirmationRow::getCardLeaseUntil, Timestamp.from(now)))
            .orderByAsc(MeetingConfirmationRow::getCardNextAttemptAt)
            .last("LIMIT " + limit));
    }
}
