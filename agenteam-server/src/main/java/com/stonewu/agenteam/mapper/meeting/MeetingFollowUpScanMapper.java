package com.stonewu.agenteam.mapper.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpScanRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 督办扫描作业的数据库语句；claim/complete/fail/block 用 claim_token 栅栏防止过期 Worker 回写。
 */
@Mapper
public interface MeetingFollowUpScanMapper extends MPJBaseMapper<MeetingFollowUpScanRow> {

    int claim(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
              @Param("token") String token, @Param("leaseUntil") Timestamp leaseUntil, @Param("now") Timestamp now);

    int complete(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
                 @Param("token") String token, @Param("resultJson") String resultJson, @Param("now") Timestamp now);

    int fail(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
             @Param("token") String token, @Param("nextAttemptAt") Timestamp nextAttemptAt,
             @Param("status") String status, @Param("errorCode") String errorCode, @Param("now") Timestamp now);

    int block(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
              @Param("token") String token, @Param("errorCode") String errorCode, @Param("now") Timestamp now);

    int markExhausted(@Param("now") Timestamp now);

    int cancelByOccurrence(@Param("enterpriseId") String enterpriseId, @Param("occurrenceId") String occurrenceId,
                           @Param("now") Timestamp now);

    default List<MeetingFollowUpScanRow> due(Timestamp now, int limit) {
        return selectList(new LambdaQueryWrapper<MeetingFollowUpScanRow>()
            .eq(MeetingFollowUpScanRow::getStatus, "queued")
            .apply("attempt_count < max_attempts")
            .le(MeetingFollowUpScanRow::getNextAttemptAt, now)
            .and(wrapper -> wrapper.isNull(MeetingFollowUpScanRow::getClaimToken)
                .or().le(MeetingFollowUpScanRow::getLeaseUntil, now))
            .orderByAsc(MeetingFollowUpScanRow::getNextAttemptAt, MeetingFollowUpScanRow::getId)
            .last("LIMIT " + limit));
    }
}
