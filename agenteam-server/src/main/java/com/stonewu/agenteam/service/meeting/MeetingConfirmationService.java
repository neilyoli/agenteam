package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSubscriptionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.meeting.entity.MeetingConfirmationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRunSnapshot;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.http.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确认闭环编排：可信回调经身份、版本与状态校验后，把负责人的确认以字段级 patch 写入
 * 飞书（同一事务推进确认轮次）；确认卡片的投递由独立的持久化投递服务承担，本服务
 * 不在数据库事务中发起网络请求。需调整意见在回答提交后以独立事务触发修订分析 Run。
 */
@Service
public class MeetingConfirmationService {
    private static final Logger LOG = LoggerFactory.getLogger(MeetingConfirmationService.class);

    private final MeetingConfirmationStore store;
    private final MeetingConfirmationCommandService commands;
    private final EnterpriseMapper enterprises;
    private final MeetingSubscriptionMapper subscriptions;
    private final RunMapper runs;
    private final MeetingEventAccessService access;
    private final MeetingEventPolicy eventPolicy;
    private final RunSubmissionService submission;
    private final Clock clock;

    public MeetingConfirmationService(MeetingConfirmationStore store, MeetingConfirmationCommandService commands,
                                      EnterpriseMapper enterprises, MeetingSubscriptionMapper subscriptions,
                                      RunMapper runs, MeetingEventAccessService access,
                                      MeetingEventPolicy eventPolicy, RunSubmissionService submission, Clock clock) {
        this.store = store;
        this.commands = commands;
        this.enterprises = enterprises;
        this.subscriptions = subscriptions;
        this.runs = runs;
        this.access = access;
        this.eventPolicy = eventPolicy;
        this.submission = submission;
        this.clock = clock;
    }

    /** 处理可信回调：去重、身份、版本与状态校验，确认写入飞书并在同一事务推进确认轮次。 */
    @Transactional
    public JsonNode answer(String enterpriseId, String bindingId, String businessKey, long taskVersion,
                           String cardMessageId, MeetingConfirmationAction action, String comment,
                           AuthContext actor, String eventId) {
        enterprises.lockEnterprise(enterpriseId).orElseThrow();
        var row = store.find(enterpriseId, bindingId, businessKey);
        if (row == null) {
            throw unavailable();
        }
        if (eventId != null && eventId.equals(row.getLastEventId())) {
            return toast("info", "此操作已处理。");
        }
        if (!MeetingConfirmationState.PENDING.value().equals(row.getStatus())) {
            throw conflict();
        }
        if (row.getTaskVersion() == null || row.getTaskVersion() != taskVersion) {
            throw conflict();
        }
        if (cardMessageId == null || !cardMessageId.equals(row.getCardMessageId())) {
            throw conflict();
        }
        if (row.getOwnerUserId() == null || !row.getOwnerUserId().equals(actor.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MEETING_CONFIRM_OWNER_REQUIRED", "仅任务负责人可以确认。");
        }
        Instant now = clock.instant();
        boolean answered = store.answer(enterpriseId, bindingId, businessKey, taskVersion, action, comment, eventId, now);
        if (!answered) {
            var current = store.find(enterpriseId, bindingId, businessKey);
            if (current != null && eventId != null && eventId.equals(current.getLastEventId())) {
                return toast("info", "此操作已处理。");
            }
            throw conflict();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(MeetingFieldCode.TASK_STATUS.code(), action.taskStatus().label());
        commands.publish(enterpriseId, bindingId, businessKey, "confirm:" + eventId, fields);
        return toast("success", messageFor(action));
    }

    /**
     * 需调整意见在回答事务提交后触发的修订分析；不在外层事务中执行，
     * 订阅、雇佣或配额不满足时记录并跳过，不影响已提交的确认回答。
     */
    public void triggerRevision(String enterpriseId, String bindingId, String businessKey, String comment) {
        try {
            var row = store.find(enterpriseId, bindingId, businessKey);
            if (row == null || !MeetingConfirmationState.NEEDS_REVISION.value().equals(row.getStatus())
                || row.getRunId() == null) {
                return;
            }
            var original = runs.find(enterpriseId, row.getRunId(), false).orElse(null);
            if (original == null) {
                LOG.warn("修订分析缺少原始执行记录，任务={}", businessKey);
                return;
            }
            var fixed = original.executionConfig();
            String subscriptionId = fixed.path("meetingSubscriptionId").asText(null);
            if (subscriptionId == null || subscriptionId.isBlank()) {
                LOG.warn("原始执行缺少订阅快照，无法触发修订分析，任务={}", businessKey);
                return;
            }
            var subscription = subscriptions.selectOne(new LambdaQueryWrapper<MeetingSubscriptionRow>()
                .eq(MeetingSubscriptionRow::getEnterpriseId, enterpriseId)
                .eq(MeetingSubscriptionRow::getId, subscriptionId));
            if (subscription == null || !"enabled".equals(subscription.getStatus())) {
                LOG.warn("会议事件订阅已停用，跳过修订分析，任务={}", businessKey);
                return;
            }
            var actor = access.requireOwner(subscription);
            var selected = eventPolicy.select(actor, subscription.getHireId(), subscription.getAgentVersionId());
            var snapshot = new MeetingEventRunSnapshot(subscription.getId(), subscription.getHireId(),
                subscription.getPluginVersion(), subscription.getAgentVersionId(),
                fixed.path("meetingIntegrationId").asText(null), selected.versionId());
            String prompt = "任务负责人要求调整会议任务（任务编号 " + businessKey + "），意见："
                + (comment == null || comment.isBlank() ? "未填写" : comment.strip())
                + "。请结合原任务与意见重新分析，修订后用 meeting_task_publish 以既有任务编号重新发布；"
                + "发布版本提升后将自动重新发起负责人确认。";
            submission.meetingRevision(actor, selected, prompt, businessKey, snapshot);
        } catch (RuntimeException failure) {
            LOG.warn("需调整意见的修订分析未触发，任务={}", businessKey, failure);
        }
    }

    private String messageFor(MeetingConfirmationAction action) {
        return switch (action) {
            case CONFIRM -> "已确认接受。";
            case REVISE -> "已记录调整意见，将重新分析后再次确认。";
            case REJECT -> "已退回，保留原因与审计。";
        };
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "MEETING_CONFIRM_UNAVAILABLE", "此确认已不存在。");
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "MEETING_CONFIRM_STALE", "任务已更新或已确认，请使用最新卡片。");
    }

    public static JsonNode toast(String type, String content) {
        var response = JsonNodeFactory.instance.objectNode();
        response.putObject("toast").put("type", type).put("content", content);
        return response;
    }
}
