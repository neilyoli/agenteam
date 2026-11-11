package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingConfirmationRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 确认闭环编排：任务同步成功后发布确认轮次并投递卡片；可信回调经身份、版本与状态校验后，
 * 把负责人的确认以字段级 patch 写入飞书，并在同一事务推进确认轮次。确认状态以飞书为唯一事实来源。
 */
@Service
public class MeetingConfirmationService {
    private final MeetingConfirmationStore store;
    private final MeetingConfirmationCommandService commands;
    private final MeetingConfirmationCardSender cards;
    private final MeetingBindingMapper bindings;
    private final EnterpriseMapper enterprises;
    private final Clock clock;

    public MeetingConfirmationService(MeetingConfirmationStore store, MeetingConfirmationCommandService commands,
                                      MeetingConfirmationCardSender cards, MeetingBindingMapper bindings,
                                      EnterpriseMapper enterprises, Clock clock) {
        this.store = store;
        this.commands = commands;
        this.cards = cards;
        this.bindings = bindings;
        this.enterprises = enterprises;
        this.clock = clock;
    }

    /** 任务同步成功后发布确认轮次；负责人未解析或未绑定则标记 unresolved，不发卡。网络投递不占数据库事务。 */
    public void publish(String enterpriseId, String bindingId, String businessKey, long taskVersion,
                        String ownerUserId, String ownerSubject, String title, String description, String runId) {
        MeetingBindingRow binding = requireBinding(enterpriseId, bindingId);
        boolean unresolved = ownerSubject == null || ownerSubject.isBlank();
        Instant now = clock.instant();
        var row = new MeetingConfirmationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterpriseId);
        row.setRevision(1L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setBindingId(bindingId);
        row.setBusinessKey(businessKey);
        row.setTaskVersion(taskVersion);
        row.setOwnerUserId(ownerUserId);
        row.setOwnerSubject(unresolved ? null : ownerSubject);
        row.setStatus(unresolved ? MeetingConfirmationState.UNRESOLVED.value() : MeetingConfirmationState.PENDING.value());
        row.setRunId(runId);
        row.setConfigJson("{}");
        store.beginRound(row, now);
        if (unresolved) {
            return;
        }
        var card = MeetingConfirmationCard.build(bindingId, businessKey, taskVersion, title, description);
        String commandId = UUID.randomUUID().toString();
        String messageId = cards.send(enterpriseId, binding.getIntegrationId(), ownerSubject, card, commandId);
        store.recordCard(enterpriseId, bindingId, businessKey, messageId, commandId, clock.instant());
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

    private String messageFor(MeetingConfirmationAction action) {
        return switch (action) {
            case CONFIRM -> "已确认接受。";
            case REVISE -> "已记录调整意见，将重新分析后再次确认。";
            case REJECT -> "已退回，保留原因与审计。";
        };
    }

    private MeetingBindingRow requireBinding(String enterpriseId, String bindingId) {
        var binding = bindings.selectOne(new LambdaQueryWrapper<MeetingBindingRow>()
            .eq(MeetingBindingRow::getEnterpriseId, enterpriseId)
            .eq(MeetingBindingRow::getId, bindingId));
        if (binding == null || !"enabled".equals(binding.getStatus())) {
            throw ResourceAuthorizationService.unavailable();
        }
        return binding;
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
