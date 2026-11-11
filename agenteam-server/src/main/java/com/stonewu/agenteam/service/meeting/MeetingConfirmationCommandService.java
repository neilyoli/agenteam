package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import com.stonewu.agenteam.model.meeting.response.MeetingSyncCommandResult;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 可信回调授权的确认写入：负责人点击卡片后，把任务状态（待确认→进行中/需调整/已拒绝）以字段级
 * patch 写入同一版本化投递箱。仅允许人工任务状态字段，且只有经过身份与版本校验的回调路径才可调用，
 * 不得向普通模型或 AI 提议字段开放可冒充确认的入口。
 */
@Service
public class MeetingConfirmationCommandService {
    private static final Set<String> CONFIRM_CODES = Set.of(MeetingFieldCode.TASK_STATUS.code());

    private final MeetingBindingMapper bindings;
    private final MeetingSyncOutbox outbox;
    private final ObjectMapper json;
    private final Clock clock;

    public MeetingConfirmationCommandService(MeetingBindingMapper bindings, MeetingSyncOutbox outbox,
                                             ObjectMapper json, Clock clock) {
        this.bindings = bindings;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public MeetingSyncCommandResult publish(String enterpriseId, String bindingId, String businessKey,
                                            String commandId, Map<String, Object> confirmFields) {
        MeetingBindingRow binding = requireEnabled(enterpriseId, bindingId);
        Map<String, String> mapping = readMapping(binding.getFieldMappingJson());
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : confirmFields.entrySet()) {
            String code = entry.getKey();
            if (!CONFIRM_CODES.contains(code)) {
                throw ApiException.invalidField("fields", "确认回调只允许写入任务状态：" + code + "。");
            }
            MeetingFieldCode field = MeetingFieldCode.of(code);
            if (field == null || field.writePolicy() != MeetingFieldWritePolicy.MANUAL) {
                throw ApiException.invalidField("fields", "确认字段必须是人工业务字段：" + code + "。");
            }
            String feishuField = mapping.get(code);
            if (feishuField == null) {
                throw ApiException.invalidField("fields", "确认字段未映射飞书字段：" + code + "。");
            }
            Object value = entry.getValue();
            if (value != null) {
                payload.put(feishuField, value);
            }
        }
        if (!payload.containsKey(mapping.get(MeetingFieldCode.TASK_STATUS.code()))) {
            throw ApiException.invalidField("fields", "任务状态不能为空。");
        }

        Instant now = clock.instant();
        MeetingSyncOutboxRow row = new MeetingSyncOutboxRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterpriseId);
        row.setBindingId(binding.getId());
        row.setBusinessKey(businessKey);
        row.setCommandId(commandId);
        row.setCreateToken(UUID.randomUUID().toString());
        row.setMaxAttempts(8);
        row.setPayloadJson(writeJson(payload));
        outbox.enqueue(row, now);
        return new MeetingSyncCommandResult(binding.getId(), businessKey, commandId);
    }

    private MeetingBindingRow requireEnabled(String enterpriseId, String bindingId) {
        var binding = bindings.selectOne(new LambdaQueryWrapper<MeetingBindingRow>()
            .eq(MeetingBindingRow::getEnterpriseId, enterpriseId)
            .eq(MeetingBindingRow::getId, bindingId));
        if (binding == null || !"enabled".equals(binding.getStatus())) {
            throw ResourceAuthorizationService.unavailable();
        }
        return binding;
    }

    private Map<String, String> readMapping(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(value, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException failure) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_BINDING_INVALID", "字段映射无法读取，请重新校验绑定。", failure);
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_COMMAND_INVALID", "确认字段无法序列化。", failure);
        }
    }
}
