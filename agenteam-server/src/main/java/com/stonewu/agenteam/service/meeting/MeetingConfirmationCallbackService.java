package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 会议确认卡片的回调入口：在聊天卡片分发中先于通用卡片处理被调用，仅识别 meeting_confirm 动作。
 * 校验机器人、绑定归属与操作者身份后，把回调交给确认闭环按版本与状态推进。
 */
@Service
public class MeetingConfirmationCallbackService {
    private final ChatBotBindingService bots;
    private final MeetingBindingMapper bindings;
    private final ChatIdentityService identities;
    private final MeetingConfirmationService confirmations;
    private final Clock clock;

    public MeetingConfirmationCallbackService(ChatBotBindingService bots, MeetingBindingMapper bindings,
                                              ChatIdentityService identities, MeetingConfirmationService confirmations,
                                              Clock clock) {
        this.bots = bots;
        this.bindings = bindings;
        this.identities = identities;
        this.confirmations = confirmations;
        this.clock = clock;
    }

    public JsonNode accept(String enterprise, String bot, Long lease, long credentialRevision,
                           long bindingRevision, JsonNode event) {
        var botRow = bots.require(enterprise, bot);
        var app = bots.application(botRow);
        if (!"enabled".equals(botRow.getStatus()) || !"feishu".equals(app.providerCode())
            || botRow.getRevision() != bindingRevision || app.credentialRevision() != credentialRevision
            || !app.externalAppId().equals(event.path("header").path("app_id").asText())
            || "websocket".equals(botRow.getTransport()) && (lease == null || botRow.getLeaseVersion() != lease.longValue()
                || botRow.getLeaseUntil() == null || !botRow.getLeaseUntil().isAfter(clock.instant()))) {
            throw unavailable();
        }
        var actionValue = event.path("event").path("action").path("value");
        String bindingId = actionValue.path("binding_id").asText();
        String businessKey = actionValue.path("task_key").asText();
        long taskVersion = actionValue.path("task_version").asLong(-1);
        MeetingConfirmationAction action = MeetingConfirmationAction.of(actionValue.path("choice").asText());
        if (bindingId.isBlank() || businessKey.isBlank() || taskVersion < 0 || action == null) {
            throw unavailable();
        }
        var binding = bindings.selectOne(new LambdaQueryWrapper<MeetingBindingRow>()
            .eq(MeetingBindingRow::getEnterpriseId, enterprise).eq(MeetingBindingRow::getId, bindingId));
        if (binding == null || !botRow.getConnectionId().equals(binding.getIntegrationId())) {
            throw unavailable();
        }
        var eventNode = event.path("event");
        var bindingRow = identities.find(enterprise, botRow.getConnectionId(), "feishu_open_id",
                eventNode.path("operator").path("open_id").asText(),
                eventNode.path("operator").path("union_id").asText(null))
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "CHANNEL_BINDING_REQUIRED", "请先绑定当前飞书账号并开启机器人聊天。"));
        var actor = identities.actor(enterprise, bindingRow.getUserId(), bindingRow.getId());
        String comment = eventNode.path("action").path("form_value").path("comment").asText(null);
        String cardMessageId = eventNode.path("context").path("open_message_id").asText();
        String eventId = event.path("header").path("event_id").asText();
        return confirmations.answer(enterprise, bindingId, businessKey, taskVersion, cardMessageId, action, comment,
            actor, eventNode.path("operator").path("open_id").asText(), eventId);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "MEETING_CONFIRM_UNAVAILABLE", "此确认卡片暂时无法操作。");
    }
}
