package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingConfirmationMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingEventMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSubscriptionMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingConfirmationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 会议事件与会议修订 Run 自动写入的唯一授权判定。只有满足全部条件时才允许
 * meeting_task_publish 免审批自动执行：Run 模式与来源固定、事件 Run 与收件箱一一对应
 * （修订 Run 与待调整确认轮次一一对应）、订阅与插件版本仍与创建时的快照一致、
 * 订阅配置者资格仍有效、任务绑定启用且归属同一飞书接入。不修改通用审批策略，
 * 不授予任何其他写工具。
 */
@Service
public class MeetingRunExecutionPolicy {
    public static final String MODE = "meeting_event";
    public static final String REVISION_MODE = "meeting_revision";
    public static final String SOURCE_TYPE = "meeting";
    public static final String BUILTIN_CODE = "meeting_management";
    public static final String PUBLISH_TOOL = "meeting_task_publish";

    private final MeetingEventMapper events;
    private final MeetingSubscriptionMapper subscriptions;
    private final MeetingBindingMapper bindings;
    private final MeetingEventPolicy policy;
    private final MeetingEventAccessService access;
    private final MeetingConfirmationMapper confirmations;

    public MeetingRunExecutionPolicy(MeetingEventMapper events, MeetingSubscriptionMapper subscriptions,
                                     MeetingBindingMapper bindings, MeetingEventPolicy policy,
                                     MeetingEventAccessService access, MeetingConfirmationMapper confirmations) {
        this.events = events;
        this.subscriptions = subscriptions;
        this.bindings = bindings;
        this.policy = policy;
        this.access = access;
        this.confirmations = confirmations;
    }

    /** 会议 Run 自动发布的完整授权上下文；修订 Run 的 event 为空。 */
    public record MeetingRunAuthorization(MeetingEventRow event, MeetingSubscriptionRow subscription,
                                          MeetingBindingRow taskBinding) {
    }

    public boolean isMeetingEventRun(RunRecord run) {
        return MODE.equals(run.mode()) && SOURCE_TYPE.equals(run.executionConfig().path("sourceType").asText());
    }

    /** 会议事件 Run 或需调整触发的会议修订 Run。 */
    public boolean isMeetingRun(RunRecord run) {
        return (MODE.equals(run.mode()) || REVISION_MODE.equals(run.mode()))
            && SOURCE_TYPE.equals(run.executionConfig().path("sourceType").asText());
    }

    /** 是否为 meeting_management 的会议任务发布工具。 */
    public boolean isMeetingPublishTool(ExecutionToolBinding binding) {
        return binding.config().path("pluginType").asText().equals("builtin")
            && BUILTIN_CODE.equals(binding.config().path("builtinCode").asText())
            && PUBLISH_TOOL.equals(binding.definition().name())
            && "write".equals(binding.definition().operationClass());
    }

    /** 非抛错版本，供权限检查快速判定；不满足时不抛出，由调用方沿用原审批规则。 */
    public boolean autoPublishAllowed(RunRecord run, ExecutionToolBinding binding) {
        if (!isMeetingRun(run) || !isMeetingPublishTool(binding)) {
            return false;
        }
        try {
            authorization(run);
            return true;
        } catch (ApiException failure) {
            return false;
        }
    }

    /** 抛错版本：任何一项不满足即拒绝自动执行。绑定与工具的匹配由调用入口保证后仍重新校验。 */
    public MeetingRunAuthorization requireAutoPublish(RunRecord run) {
        if (!isMeetingRun(run)) {
            throw unsupported();
        }
        return authorization(run);
    }

    private MeetingRunAuthorization authorization(RunRecord run) {
        var fixed = run.executionConfig();
        MeetingEventRow event = null;
        if (MODE.equals(run.mode())) {
            event = requireEventLink(run, fixed);
        } else {
            requireRevisionLink(run, fixed);
        }
        String subscriptionId = fixed.path("meetingSubscriptionId").asText(null);
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw mismatch("本次会议执行缺少固定的订阅快照。");
        }
        MeetingSubscriptionRow subscription = subscriptions.selectOne(
            new LambdaQueryWrapper<MeetingSubscriptionRow>()
                .eq(MeetingSubscriptionRow::getEnterpriseId, run.enterpriseId())
                .eq(MeetingSubscriptionRow::getId, subscriptionId));
        if (subscription == null || !"enabled".equals(subscription.getStatus())) {
            throw mismatch("会议事件订阅已被停用或删除。");
        }
        String integrationId = event != null ? event.getIntegrationId()
            : fixed.path("meetingIntegrationId").asText(null);
        if (integrationId == null || !subscription.getIntegrationId().equals(integrationId)) {
            throw mismatch("会议事件订阅与事件来源接入不一致。");
        }
        if (!Objects.equals(subscription.getHireId(), fixed.path("meetingHireId").asText(null))) {
            throw mismatch("会议事件订阅的雇佣已变化。");
        }
        if (!Objects.equals(subscription.getPluginVersion(), fixed.path("meetingPluginVersion").asText(null))) {
            throw mismatch("会议事件订阅的插件版本已变化。");
        }
        if (!Objects.equals(subscription.getAgentVersionId(), fixed.path("meetingAgentVersionId").asText(null))) {
            throw mismatch("会议事件订阅固定的智能体版本已变化。");
        }
        if (!subscription.getOwnerUserId().equals(run.userId())) {
            throw mismatch("本次执行身份与订阅配置者不一致。");
        }
        if (fixed.hasNonNull("meetingSelectedVersionId")
            && !Objects.equals(fixed.path("meetingSelectedVersionId").asText(), run.agentVersionId())) {
            throw mismatch("执行使用的智能体版本与创建时的选择不一致。");
        }
        if (!policy.pluginActive(run.enterpriseId(), subscription.getPluginVersion())) {
            throw mismatch("会议插件当前安装版本与订阅固定版本不一致。");
        }
        access.requireOwner(subscription);
        MeetingBindingRow taskBinding = bindings.selectOne(new LambdaQueryWrapper<MeetingBindingRow>()
            .eq(MeetingBindingRow::getEnterpriseId, run.enterpriseId())
            .eq(MeetingBindingRow::getBusinessType, MeetingBusinessType.TASK.code()));
        if (taskBinding == null || !"enabled".equals(taskBinding.getStatus())) {
            throw mismatch("企业任务督办宽表绑定未启用。");
        }
        if (!taskBinding.getIntegrationId().equals(integrationId)) {
            throw mismatch("任务绑定与会议事件不属于同一飞书接入。");
        }
        return new MeetingRunAuthorization(event, subscription, taskBinding);
    }

    /** 事件 Run 必须与收件箱 routed 记录一一对应，事件字段与快照一致。 */
    private MeetingEventRow requireEventLink(RunRecord run, JsonNode fixed) {
        String eventId = fixed.path("meetingEventId").asText(null);
        if (eventId == null || eventId.isBlank()) {
            throw mismatch("本次会议事件执行缺少固定的事件关联。");
        }
        MeetingEventRow event = events.selectOne(new LambdaQueryWrapper<MeetingEventRow>()
            .eq(MeetingEventRow::getEnterpriseId, run.enterpriseId()).eq(MeetingEventRow::getId, eventId));
        if (event == null) {
            throw mismatch("本次会议事件收件箱记录已不存在。");
        }
        if (!"routed".equals(event.getStatus()) || !run.id().equals(event.getRunId())) {
            throw mismatch("会议事件与当前执行的关联已变化。");
        }
        if (!event.getIntegrationId().equals(fixed.path("meetingIntegrationId").asText())) {
            throw mismatch("会议事件来源接入与执行快照不一致。");
        }
        if (event.getEventType() != null && !event.getEventType().equals(fixed.path("meetingEventType").asText())) {
            throw mismatch("会议事件类型与执行快照不一致。");
        }
        if (event.getMeetingId() != null && fixed.hasNonNull("meetingId")
            && !event.getMeetingId().equals(fixed.path("meetingId").asText())) {
            throw mismatch("会议编号与执行快照不一致。");
        }
        return event;
    }

    /** 修订 Run 必须对应仍处于需调整状态的确认轮次，任务已重新确认后旧修订执行失效。 */
    private void requireRevisionLink(RunRecord run, JsonNode fixed) {
        String taskKey = fixed.path("meetingRevisionTaskKey").asText(null);
        if (taskKey == null || taskKey.isBlank()) {
            throw mismatch("本次会议修订执行缺少固定的任务关联。");
        }
        var confirmation = confirmations.selectOne(new LambdaQueryWrapper<MeetingConfirmationRow>()
            .eq(MeetingConfirmationRow::getEnterpriseId, run.enterpriseId())
            .eq(MeetingConfirmationRow::getBusinessKey, taskKey)
            .last("LIMIT 1"));
        if (confirmation == null) {
            throw mismatch("会议修订执行对应的确认轮次已不存在。");
        }
        if (!MeetingConfirmationState.NEEDS_REVISION.value().equals(confirmation.getStatus())) {
            throw mismatch("任务确认状态已变化，本次修订执行不再有效。");
        }
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.CONFLICT, "PLATFORM_WRITE_MODE_UNSUPPORTED",
            "请在普通对话中确认并修改平台内容，预览和计划执行不能修改这些内容。");
    }

    private static ApiException mismatch(String reason) {
        return new ApiException(HttpStatus.FORBIDDEN, "MEETING_RUN_AUTHORIZATION_INVALID", reason);
    }
}
