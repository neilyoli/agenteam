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
import java.util.Set;

/**
 * 一次提交只处理数据库；Run 创建与收件箱回写在同一事务，崩溃不会产生重复 Run。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class MeetingEventTransactions {
    /** 资料就绪前最多等待的领取次数；超出后终止等待并标记失败，避免无限重试。 */
    public static final int ARTIFACT_WAIT_MAX_ATTEMPTS = 6;
    private static final Set<String> MEETING_ENDED_TYPES = Set.of("meeting.ended", "meeting.all_meeting.ended");

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
        if (!"received".equals(row.getStatus()) && !"waiting".equals(row.getStatus())) {
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
        if (artifactsPending(row)) {
            waitForArtifacts(lease, row);
            return;
        }
        var snapshot = new com.stonewu.agenteam.model.meeting.entity.MeetingEventRunSnapshot(
            subscription.getId(), subscription.getHireId(), subscription.getPluginVersion(),
            subscription.getAgentVersionId(), row.getIntegrationId(), selected.versionId());
        var accepted = submission.meetingEvent(actor, selected, meetingPrompt(row), row.getId(), row.getEventType(),
            row.getMeetingId(), row.getMinuteToken(), 0, snapshot);
        if (accepted.isEmpty()) {
            store.markIgnored(row, "QUOTA_EXCEEDED");
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        store.markRouted(row, accepted.get().runId(), accepted.get().conversationId());
        store.finish(lease, "completed", null, null, clock.instant());
    }

    /** 会议结束事件尚未取得 minute_token（纪要未就绪）时，落 waiting 持久等待并由退避重试恢复，不忙等。 */
    private void waitForArtifacts(JobLease lease, MeetingEventRow row) {
        if (lease.attemptCount() >= ARTIFACT_WAIT_MAX_ATTEMPTS) {
            store.markFailed(row, "MEETING_ARTIFACT_TIMEOUT");
            store.finish(lease, "failed", "MEETING_ARTIFACT_TIMEOUT", "会议资料超时未就绪，已停止等待。", clock.instant());
            return;
        }
        var waitSeconds = artifactWaitSeconds(lease.attemptCount());
        store.markWaiting(row, clock.instant().plusSeconds(waitSeconds));
        store.finish(lease, "queued", "MEETING_ARTIFACT_PENDING", "会议资料尚未就绪，稍后重试。",
            clock.instant().plusSeconds(waitSeconds));
    }

    /** 纪要尚不可读：meeting.ended / meeting.all_meeting.ended 事件尚未携带 minute_token。 */
    private static boolean artifactsPending(MeetingEventRow row) {
        return MEETING_ENDED_TYPES.contains(row.getEventType())
            && (row.getMinuteToken() == null || row.getMinuteToken().isBlank());
    }

    /** 资料等待退避：min(300, 60 * 尝试次数) 秒，随尝试次数递增但封顶。 */
    static long artifactWaitSeconds(int attemptCount) {
        return Math.min(300, 60L * Math.max(1, attemptCount));
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
        if (!"received".equals(row.getStatus()) && !"waiting".equals(row.getStatus())) {
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
