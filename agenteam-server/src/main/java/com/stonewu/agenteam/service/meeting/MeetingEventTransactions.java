package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRow;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * 一次提交只处理数据库；Run 创建与收件箱回写在同一事务，崩溃不会产生重复 Run。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class MeetingEventTransactions {
    private final BackgroundJobMapper jobs;
    private final EnterpriseMapper enterprises;
    private final MeetingEventStore store;
    private final MeetingEventAccessService access;
    private final MeetingEventPolicy policy;
    private final RunSubmissionService submission;
    private final ResourceJson json;
    private final Clock clock;

    public MeetingEventTransactions(BackgroundJobMapper jobs, EnterpriseMapper enterprises, MeetingEventStore store,
                                    MeetingEventAccessService access, MeetingEventPolicy policy,
                                    RunSubmissionService submission, ResourceJson json, Clock clock) {
        this.jobs = jobs;
        this.enterprises = enterprises;
        this.store = store;
        this.access = access;
        this.policy = policy;
        this.submission = submission;
        this.json = json;
        this.clock = clock;
    }

    public Optional<JobLease> claim(String worker) {
        return jobs.claim("meeting_event", worker, clock.instant());
    }

    public boolean renew(JobLease lease) {
        return jobs.renew(lease, clock.instant());
    }

    public void submit(JobLease lease) {
        var row = lock(lease);
        if (row == null) {
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        if ("routed".equals(row.getStatus()) || row.getRunId() != null) {
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        if (!"received".equals(row.getStatus())) {
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        if (lease.exhausted()) {
            store.markFailed(row, "MEETING_EVENT_EXHAUSTED");
            store.finish(lease, "failed", "MEETING_EVENT_EXHAUSTED", "本次会议事件多次处理失败。", clock.instant());
            return;
        }
        var subscription = policy.findEnabled(lease.enterpriseId(), row.getIntegrationId(), row.getEventType());
        if (subscription == null) {
            store.markIgnored(row, "SUBSCRIPTION_NOT_MATCHED");
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        if (!policy.pluginActive(lease.enterpriseId(), subscription.getPluginVersion())) {
            store.markIgnored(row, "PLUGIN_VERSION_MISMATCH");
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        var actor = access.requireOwner(subscription);
        var selected = policy.select(actor, subscription.getHireId(), subscription.getAgentVersionId());
        var accepted = submission.meetingEvent(actor, selected, meetingPrompt(row), row.getId(), row.getEventType(),
            row.getMeetingId(), row.getMinuteToken(), 0);
        if (accepted.isEmpty()) {
            store.markIgnored(row, "QUOTA_EXCEEDED");
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        store.markRouted(row, accepted.get().runId(), accepted.get().conversationId());
        store.finish(lease, "completed", null, null, clock.instant());
    }

    /** 提交事务已经整体回滚后，单独保存权限拒绝或可重试的系统失败。 */
    public void failure(JobLease lease, RuntimeException failure) {
        var row = lock(lease);
        if (row == null) {
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        if ("routed".equals(row.getStatus()) || row.getRunId() != null) {
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        if (!"received".equals(row.getStatus())) {
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        String code = "MEETING_EVENT_PROCESSING_FAILED";
        String summary = "本次会议事件暂时无法处理，稍后会继续尝试。";
        boolean terminal = lease.exhausted() || lease.attemptCount() >= lease.maxAttempts();
        if (failure instanceof ApiException api) {
            code = api.code();
            int status = api.getStatusCode().value();
            if (status == 403 || status == 404) {
                summary = "执行所需的账号、权限或资源配置已不可用，本次会议事件未提交。";
                terminal = true;
            }
        }
        if (terminal) {
            store.markFailed(row, code);
        }
        store.finish(lease, terminal ? "failed" : "queued", code, summary,
            clock.instant().plusSeconds(Math.min(60, 5L * lease.attemptCount())));
    }

    private MeetingEventRow lock(JobLease lease) {
        enterprises.lockEnterprise(lease.enterpriseId())
            .orElseThrow(() -> new IllegalStateException("会议事件所属企业记录不存在"));
        var payload = json.read(lease.payloadJson());
        var row = store.find(lease.enterpriseId(), payload.path("meetingEventId").asText());
        if (row == null || !lease.id().equals(row.getJobId())) {
            return null;
        }
        return row;
    }

    private String meetingPrompt(MeetingEventRow row) {
        return "请分析会议" + (row.getMeetingId() == null ? "事件" : " " + row.getMeetingId()) + "的纪要内容，提取待办任务候选，"
            + "并按需要生成负责人确认卡片。";
    }
}
