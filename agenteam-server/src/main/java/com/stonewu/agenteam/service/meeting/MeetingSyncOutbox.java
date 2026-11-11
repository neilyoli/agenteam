package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSyncOutboxSqlMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 版本化投递箱的持久化操作与领取栅栏。领取用 claim_token + lease_until 表达，
 * 过期 Worker 的完成/失败/绑定回写因 token 不匹配而失效，避免覆盖新领取者。
 */
@Service
public class MeetingSyncOutbox {
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final MeetingSyncOutboxSqlMapper statements;

    public MeetingSyncOutbox(MeetingSyncOutboxSqlMapper statements) {
        this.statements = statements;
    }

    /** 领取成功后的栅栏持有；携带领取时刻的期望版本与投递快照。 */
    public record Attempt(MeetingSyncOutboxRow row, String token) {
    }

    public void enqueue(MeetingSyncOutboxRow row, Instant now) {
        statements.upsert(row, Timestamp.from(now));
    }

    public Optional<Attempt> claim(String bindingId, String businessKey, Instant now) {
        String token = UUID.randomUUID().toString();
        int claimed = statements.claim(bindingId, businessKey, token,
            Timestamp.from(now.plus(LEASE)), Timestamp.from(now));
        if (claimed == 0) {
            return Optional.empty();
        }
        MeetingSyncOutboxRow row = statements.selectOne(new LambdaQueryWrapper<MeetingSyncOutboxRow>()
            .eq(MeetingSyncOutboxRow::getBindingId, bindingId)
            .eq(MeetingSyncOutboxRow::getBusinessKey, businessKey)
            .eq(MeetingSyncOutboxRow::getClaimToken, token));
        return row == null ? Optional.empty() : Optional.of(new Attempt(row, token));
    }

    public void bindRecord(Attempt attempt, String recordId, Instant now) {
        statements.bindRecord(attempt.row().getBindingId(), attempt.row().getBusinessKey(),
            attempt.token(), recordId, Timestamp.from(now));
    }

    public void complete(Attempt attempt, Instant now) {
        statements.complete(attempt.row().getBindingId(), attempt.row().getBusinessKey(),
            attempt.token(), attempt.row().getDesiredVersion(), Timestamp.from(now), Timestamp.from(now));
    }

    public void fail(Attempt attempt, String errorCode, String requestId, Instant now) {
        int attemptCount = attempt.row().getAttemptCount();
        int maxAttempts = attempt.row().getMaxAttempts();
        statements.fail(attempt.row().getBindingId(), attempt.row().getBusinessKey(), attempt.token(),
            Timestamp.from(backoff(now, attemptCount)), attemptCount >= maxAttempts ? "failed" : "pending",
            errorCode, requestId, Timestamp.from(now));
    }

    public void block(Attempt attempt, String errorCode, String requestId, Instant now) {
        statements.block(attempt.row().getBindingId(), attempt.row().getBusinessKey(),
            attempt.token(), errorCode, requestId, Timestamp.from(now));
    }

    public void retry(String bindingId, String businessKey, Instant now) {
        statements.retry(bindingId, businessKey, Timestamp.from(now));
    }

    public void markExhausted(Instant now) {
        statements.markExhausted(Timestamp.from(now));
    }

    public List<MeetingSyncOutboxRow> due(Instant now, int limit) {
        return statements.due(Timestamp.from(now), limit);
    }

    /** 失败退避：min(300, 5 &lt;&lt; min(6, attemptCount-1)) 秒，并叠加少量抖动避免蜂拥。 */
    static Instant backoff(Instant now, int attemptCount) {
        int index = Math.min(6, Math.max(0, attemptCount - 1));
        long seconds = Math.min(300, 5L << index);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, seconds / 2));
        return now.plusSeconds(seconds + jitter);
    }
}
