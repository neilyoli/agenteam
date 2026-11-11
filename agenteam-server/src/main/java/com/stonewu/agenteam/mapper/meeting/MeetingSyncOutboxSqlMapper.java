package com.stonewu.agenteam.mapper.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 版本化投递箱的数据库语句；入队 upsert 与领取栅栏由 XML 提供，扫描用类型化条件。
 */
@Mapper
public interface MeetingSyncOutboxSqlMapper extends MPJBaseMapper<MeetingSyncOutboxRow> {

    int upsert(@Param("row") MeetingSyncOutboxRow row, @Param("now") Timestamp now);

    int claim(@Param("bindingId") String bindingId, @Param("businessKey") String businessKey,
              @Param("token") String token, @Param("leaseUntil") Timestamp leaseUntil, @Param("now") Timestamp now);

    int bindRecord(@Param("bindingId") String bindingId, @Param("businessKey") String businessKey,
                   @Param("token") String token, @Param("recordId") String recordId, @Param("now") Timestamp now);

    int complete(@Param("bindingId") String bindingId, @Param("businessKey") String businessKey,
                 @Param("token") String token, @Param("version") long version,
                 @Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("now") Timestamp now);

    int fail(@Param("bindingId") String bindingId, @Param("businessKey") String businessKey,
             @Param("token") String token, @Param("nextAttemptAt") Timestamp nextAttemptAt,
             @Param("status") String status, @Param("errorCode") String errorCode,
             @Param("requestId") String requestId, @Param("now") Timestamp now);

    int block(@Param("bindingId") String bindingId, @Param("businessKey") String businessKey,
              @Param("token") String token, @Param("errorCode") String errorCode,
              @Param("requestId") String requestId, @Param("now") Timestamp now);

    int retry(@Param("bindingId") String bindingId, @Param("businessKey") String businessKey,
              @Param("now") Timestamp now);

    int markExhausted(@Param("now") Timestamp now);

    default List<MeetingSyncOutboxRow> due(Timestamp now, int limit) {
        return selectList(new LambdaQueryWrapper<MeetingSyncOutboxRow>()
            .eq(MeetingSyncOutboxRow::getStatus, "pending")
            .apply("delivered_version < desired_version")
            .apply("attempt_count < max_attempts")
            .le(MeetingSyncOutboxRow::getNextAttemptAt, now)
            .and(wrapper -> wrapper.isNull(MeetingSyncOutboxRow::getClaimToken)
                .or().le(MeetingSyncOutboxRow::getLeaseUntil, now))
            .orderByAsc(MeetingSyncOutboxRow::getNextAttemptAt, MeetingSyncOutboxRow::getId)
            .last("LIMIT " + limit));
    }
}
