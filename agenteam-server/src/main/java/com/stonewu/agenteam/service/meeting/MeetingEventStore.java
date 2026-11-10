package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingEventMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 收件箱记录与工作完成在同一事务提交；租约变化时全部业务写入随异常回滚。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class MeetingEventStore {
    private final MeetingEventMapper events;
    private final BackgroundJobMapper jobs;
    private final Clock clock;

    public MeetingEventStore(MeetingEventMapper events, BackgroundJobMapper jobs, Clock clock) {
        this.events = events;
        this.jobs = jobs;
        this.clock = clock;
    }

    public MeetingEventRow find(String enterprise, String id) {
        return events.selectOne(new LambdaQueryWrapper<MeetingEventRow>()
            .eq(MeetingEventRow::getEnterpriseId, enterprise).eq(MeetingEventRow::getId, id));
    }

    public void markRouted(MeetingEventRow row, String runId, String conversationId) {
        row.setStatus("routed");
        row.setRunId(runId);
        row.setConversationId(conversationId);
        save(row);
    }

    public void markIgnored(MeetingEventRow row, String reason) {
        row.setStatus("ignored");
        row.setReasonCode(reason);
        save(row);
    }

    public void markFailed(MeetingEventRow row, String errorCode) {
        row.setStatus("failed");
        row.setErrorCode(errorCode);
        save(row);
    }

    private void save(MeetingEventRow row) {
        row.setUpdatedAt(clock.instant());
        int changed = events.update(new LambdaUpdateWrapper<MeetingEventRow>()
            .eq(MeetingEventRow::getEnterpriseId, row.getEnterpriseId()).eq(MeetingEventRow::getId, row.getId())
            .set(MeetingEventRow::getStatus, row.getStatus()).set(MeetingEventRow::getReasonCode, row.getReasonCode())
            .set(MeetingEventRow::getRunId, row.getRunId()).set(MeetingEventRow::getConversationId, row.getConversationId())
            .set(MeetingEventRow::getErrorCode, row.getErrorCode()).set(MeetingEventRow::getUpdatedAt, row.getUpdatedAt())
            .setIncrBy(MeetingEventRow::getRevision, 1));
        if (changed != 1) {
            throw new IllegalStateException("会议事件收件箱记录没有保存成功");
        }
    }

    public void finish(JobLease lease, String status, String code, String summary, Instant availableAt) {
        if (!jobs.lockOwned(lease, clock.instant()) || !jobs.finish(lease, status, code, summary, availableAt,
            clock.instant())) {
            throw new LeaseLostException();
        }
    }

    public static class LeaseLostException extends RuntimeException {
        public LeaseLostException() {
            super("会议事件的工作租约已变化，本次提交已回滚");
        }
    }
}
