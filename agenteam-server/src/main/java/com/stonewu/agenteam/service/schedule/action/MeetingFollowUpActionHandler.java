package com.stonewu.agenteam.service.schedule.action;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpScanRow;
import com.stonewu.agenteam.model.meeting.request.MeetingFollowUpActionRequest;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionDefinition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionSubmission;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.meeting.MeetingFollowUpPolicy;
import com.stonewu.agenteam.service.meeting.MeetingFollowUpStore;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import com.stonewu.agenteam.service.schedule.ScheduleActionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 会议督办操作：复用宿主 Schedule 触发，排入扫描作业后由独立工作进程读取飞书、限频并催办。
 * 提交与取消只写数据库，网络与飞书调用留给工作进程；不使用智能体或模型额度。
 */
@Component
public class MeetingFollowUpActionHandler implements ScheduleActionHandler {
    private static final int MAX_ATTEMPTS = 8;

    private final EnterpriseAuthorizationService authorization;
    private final MeetingBindingMapper bindings;
    private final MeetingFollowUpStore store;
    private final MeetingFollowUpResultService results;
    private final ObjectMapper json;
    private final Clock clock;

    public MeetingFollowUpActionHandler(EnterpriseAuthorizationService authorization, MeetingBindingMapper bindings,
                                        MeetingFollowUpStore store, MeetingFollowUpResultService results,
                                        ObjectMapper json, Clock clock) {
        this.authorization = authorization;
        this.bindings = bindings;
        this.store = store;
        this.results = results;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String type() {
        return "meeting.follow_up";
    }

    @Override
    public String name() {
        return "会议督办跟进";
    }

    @Override
    public boolean usesAgent() {
        return false;
    }

    @Override
    public Map<String, Object> configurationSchema() {
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("bindingId", "cooldownMinutes"),
            "properties", Map.of(
                "bindingId", Map.of("type", "string", "minLength", 1, "maxLength", 100),
                "cooldownMinutes", Map.of("type", "integer", "minimum", 1, "maximum", 43200),
                "windowStartHour", Map.of("type", "integer", "minimum", 0, "maximum", 23),
                "windowEndHour", Map.of("type", "integer", "minimum", 0, "maximum", 23),
                "conditions", Map.of("type", "array", "minItems", 1, "maxItems", 3, "items", Map.of("type", "string"))));
    }

    @Override
    public boolean available(AuthContext actor) {
        return actor.permissions().contains("schedule.manage");
    }

    @Override
    public ScheduleActionDefinition prepare(AuthContext actor, ScheduleWriteRequest input, ScheduleRecord previous) {
        authorization.require(actor, "schedule.manage");
        var request = InputValidation.read(json.valueToTree(input.action().config()), MeetingFollowUpActionRequest.class, "action.config");
        if (input.maxRetries() != 0) {
            throw ApiException.invalidField("maxRetries", "督办操作不使用智能体重试次数，请填写零。");
        }
        String bindingId = ResourceInput.text(request.bindingId(), "action.config.bindingId", 100, true);
        requireTaskBinding(actor, bindingId);
        long cooldown = request.cooldownMinutes() == null ? 1440 : request.cooldownMinutes();
        if (cooldown < 1 || cooldown > 43200) {
            throw ApiException.invalidField("action.config.cooldownMinutes", "提醒最小间隔需在一分钟至三十天之间。");
        }
        int start = request.windowStartHour() == null ? 9 : request.windowStartHour();
        int end = request.windowEndHour() == null ? 18 : request.windowEndHour();
        if (start < 0 || start > 23 || end < 0 || end > 23 || start >= end) {
            throw ApiException.invalidField("action.config.windowStartHour", "工作时段需要在 0 至 23 点之间且开始早于结束。");
        }
        Set<String> conditions = request.conditions() == null || request.conditions().isEmpty()
            ? MeetingFollowUpPolicy.SUPPORTED_CONDITIONS
            : new LinkedHashSet<>(request.conditions());
        if (!MeetingFollowUpPolicy.SUPPORTED_CONDITIONS.containsAll(conditions)) {
            throw ApiException.invalidField("action.config.conditions", "跟进条件仅支持未确认、逾期与上游未覆盖。");
        }
        MeetingFollowUpPolicy policy = new MeetingFollowUpPolicy(bindingId, cooldown, start, end, Set.copyOf(conditions));
        return new ScheduleActionDefinition(type(), 1, writeJson(policy), null, null, null, 0, List.of());
    }

    @Override
    public void validateCurrent(AuthContext actor, ScheduleRecord schedule) {
        authorization.require(actor, "schedule.manage");
        requireTaskBinding(actor, readPolicy(schedule.actionConfigJson()).bindingId());
    }

    @Override
    public JsonNode snapshot(ScheduleRecord schedule) {
        try {
            return json.readTree(schedule.actionConfigJson());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("已保存的督办策略无法读取", failure);
        }
    }

    @Override
    public Map<String, Object> publicConfiguration(ScheduleRecord schedule) {
        MeetingFollowUpPolicy policy = readPolicy(schedule.actionConfigJson());
        return Map.of("bindingId", policy.bindingId(), "cooldownMinutes", policy.cooldownMinutes(),
            "windowStartHour", policy.windowStartHour(), "windowEndHour", policy.windowEndHour(),
            "conditions", policy.conditions());
    }

    @Override
    public ScheduleActionSubmission submit(AuthContext actor, ScheduledOccurrenceRow occurrence) {
        authorization.require(actor, "schedule.manage");
        MeetingFollowUpPolicy policy = readPolicy(occurrence.getActionSnapshotJson());
        requireTaskBinding(actor, policy.bindingId());
        Instant now = clock.instant();
        MeetingFollowUpScanRow row = new MeetingFollowUpScanRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(actor.enterpriseId());
        row.setRevision(1L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setBindingId(policy.bindingId());
        row.setScheduleId(occurrence.getScheduleId());
        row.setOccurrenceId(occurrence.getId());
        row.setPolicyJson(occurrence.getActionSnapshotJson());
        row.setStatus("queued");
        row.setAttemptCount(0);
        row.setMaxAttempts(MAX_ATTEMPTS);
        store.enqueue(row, now);
        var result = json.createObjectNode().put("prepared", true).put("enqueued", true);
        return new ScheduleActionSubmission("running", null, null, null, result);
    }

    @Override
    public void cancel(AuthContext actor, ScheduledOccurrenceRow occurrence) {
        store.cancelOccurrence(occurrence.getEnterpriseId(), occurrence.getId(), clock.instant());
    }

    @Override
    public Optional<ScheduleActionSubmission> reconcile(ScheduledOccurrenceRow occurrence) {
        return Optional.of(results.summarize(occurrence));
    }

    private void requireTaskBinding(AuthContext actor, String bindingId) {
        MeetingBindingRow binding = bindings.selectOne(new LambdaQueryWrapper<MeetingBindingRow>()
            .eq(MeetingBindingRow::getEnterpriseId, actor.enterpriseId())
            .eq(MeetingBindingRow::getId, bindingId));
        if (binding == null || "deleted".equals(binding.getStatus())) {
            throw ResourceAuthorizationService.unavailable();
        }
        if (!"enabled".equals(binding.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_BINDING_DISABLED", "所选会议绑定未启用，请先启用绑定。");
        }
        if (!"task".equals(binding.getBusinessType())) {
            throw ApiException.invalidField("action.config.bindingId", "督办仅支持会议督办宽表（task）绑定。");
        }
    }

    private MeetingFollowUpPolicy readPolicy(String value) {
        try {
            return json.readValue(value, MeetingFollowUpPolicy.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("已保存的督办策略无法读取", failure);
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("督办策略无法保存", failure);
        }
    }
}
