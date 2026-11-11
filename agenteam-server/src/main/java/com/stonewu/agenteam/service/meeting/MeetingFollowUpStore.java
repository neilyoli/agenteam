package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.meeting.MeetingFollowUpMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingFollowUpScanMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpScanRow;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 督办台账与扫描作业的持久化栅栏。remind 以 next_reminder_at 原子抢占限频槽位，
 * 扫描 claim/complete/fail/block 用 claim_token + lease_until 防止过期 Worker 回写。
 */
@Service
public class MeetingFollowUpStore {
    private static final Duration LEASE = Duration.ofSeconds(120);

    private final MeetingFollowUpMapper ledgers;
    private final MeetingFollowUpScanMapper scans;

    public MeetingFollowUpStore(MeetingFollowUpMapper ledgers, MeetingFollowUpScanMapper scans) {
        this.ledgers = ledgers;
        this.scans = scans;
    }

    /** 领取成功后扫描作业的栅栏持有。 */
    public record Attempt(MeetingFollowUpScanRow row, String token) {
    }

    // ---- 台账（逐任务限频） ----

    public void insertLedger(MeetingFollowUpRow row) {
        ledgers.insert(row);
    }

    public MeetingFollowUpRow find(String enterpriseId, String bindingId, String businessKey) {
        return ledgers.find(enterpriseId, bindingId, businessKey);
    }

    /** 原子抢占限频槽位：到期望时刻可催办时置 next_reminder_at 并写证据，返回是否抢占成功。 */
    public boolean remind(String enterpriseId, String bindingId, String businessKey, Instant now,
                          Instant nextReminderAt, String evidenceJson) {
        return ledgers.remind(enterpriseId, bindingId, businessKey, Timestamp.from(now),
            Timestamp.from(nextReminderAt), evidenceJson) == 1;
    }

    /** 催办成功后回填实际时间并累计次数；失败不调用，避免误计成功。 */
    public void recordSent(String enterpriseId, String bindingId, String businessKey, Instant now) {
        ledgers.recordSent(enterpriseId, bindingId, businessKey, Timestamp.from(now));
    }

    // ---- 扫描作业（逐发生记录） ----

    public void enqueue(MeetingFollowUpScanRow row, Instant now) {
        if (row.getNextAttemptAt() == null) {
            row.setNextAttemptAt(now);
        }
        scans.insert(row);
    }

    public Optional<Attempt> claim(String enterpriseId, String id, Instant now) {
        String token = UUID.randomUUID().toString();
        int claimed = scans.claim(enterpriseId, id, token, Timestamp.from(now.plus(LEASE)), Timestamp.from(now));
        if (claimed == 0) {
            return Optional.empty();
        }
        MeetingFollowUpScanRow row = scans.selectOne(new LambdaQueryWrapper<MeetingFollowUpScanRow>()
            .eq(MeetingFollowUpScanRow::getEnterpriseId, enterpriseId)
            .eq(MeetingFollowUpScanRow::getId, id)
            .eq(MeetingFollowUpScanRow::getClaimToken, token));
        return row == null ? Optional.empty() : Optional.of(new Attempt(row, token));
    }

    public void complete(Attempt attempt, String resultJson, Instant now) {
        scans.complete(attempt.row().getEnterpriseId(), attempt.row().getId(), attempt.token(), resultJson, Timestamp.from(now));
    }

    public void fail(Attempt attempt, String errorCode, Instant now) {
        int count = attempt.row().getAttemptCount();
        int max = attempt.row().getMaxAttempts();
        scans.fail(attempt.row().getEnterpriseId(), attempt.row().getId(), attempt.token(),
            Timestamp.from(backoff(now, count)), count >= max ? "failed" : "queued", errorCode, Timestamp.from(now));
    }

    public void block(Attempt attempt, String errorCode, Instant now) {
        scans.block(attempt.row().getEnterpriseId(), attempt.row().getId(), attempt.token(), errorCode, Timestamp.from(now));
    }

    public void markExhausted(Instant now) {
        scans.markExhausted(Timestamp.from(now));
    }

    /** 计划取消时阻断该发生记录尚未处理的扫描作业，避免取消后仍催办。 */
    public void cancelOccurrence(String enterpriseId, String occurrenceId, Instant now) {
        scans.cancelByOccurrence(enterpriseId, occurrenceId, Timestamp.from(now));
    }

    public List<MeetingFollowUpScanRow> due(Instant now, int limit) {
        return scans.due(Timestamp.from(now), limit);
    }

    public List<MeetingFollowUpScanRow> scansFor(String enterpriseId, String occurrenceId) {
        return scans.selectList(new LambdaQueryWrapper<MeetingFollowUpScanRow>()
            .eq(MeetingFollowUpScanRow::getEnterpriseId, enterpriseId)
            .eq(MeetingFollowUpScanRow::getOccurrenceId, occurrenceId)
            .orderByAsc(MeetingFollowUpScanRow::getCreatedAt, MeetingFollowUpScanRow::getId));
    }

    /** 失败退避：min(300, 5 &lt;&lt; min(6, attemptCount-1)) 秒，并叠加少量抖动避免蜂拥。 */
    static Instant backoff(Instant now, int attemptCount) {
        int index = Math.min(6, Math.max(0, attemptCount - 1));
        long seconds = Math.min(300, 5L << index);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, seconds / 2));
        return now.plusSeconds(seconds + jitter);
    }
}
