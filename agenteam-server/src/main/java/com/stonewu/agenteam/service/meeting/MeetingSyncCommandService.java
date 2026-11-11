package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import com.stonewu.agenteam.model.meeting.request.MeetingSyncCommand;
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
import java.util.UUID;

/**
 * 业务候选提交与投递箱命令在同一宿主持久化事务中完成。业务字段经白名单与写入所有权校验，
 * 映射为飞书字段级 patch 后入队；人工字段以飞书为唯一事实来源，绝不进入投递 payload。
 */
@Service
public class MeetingSyncCommandService {
    private static final int DEFAULT_MAX_ATTEMPTS = 8;

    private final MeetingBindingMapper bindings;
    private final MeetingSyncOutbox outbox;
    private final ObjectMapper json;
    private final Clock clock;

    public MeetingSyncCommandService(MeetingBindingMapper bindings, MeetingSyncOutbox outbox,
                                     ObjectMapper json, Clock clock) {
        this.bindings = bindings;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public MeetingSyncCommandResult publish(String enterpriseId, MeetingSyncCommand command) {
        if (command.commandId() == null || command.commandId().isBlank()) {
            throw ApiException.invalidField("commandId", "必须提供业务命令标识。");
        }
        MeetingBindingRow binding = requireEnabled(enterpriseId, command.bindingId());
        MeetingBusinessType type = MeetingBusinessType.of(binding.getBusinessType());
        Map<String, String> mapping = readMapping(binding.getFieldMappingJson());
        MeetingFieldCode uniqueKey = MeetingFieldCode.uniqueKeyFor(type);

        Map<String, Object> payload = new LinkedHashMap<>();
        String businessKey = null;
        for (Map.Entry<String, Object> entry : command.fields().entrySet()) {
            String businessCode = entry.getKey();
            Object value = entry.getValue();
            MeetingFieldCode code = MeetingFieldCode.of(businessCode);
            if (code == null) {
                throw ApiException.invalidField("fields", "业务字段编码不在白名单内：" + businessCode + "。");
            }
            if (code.writePolicy() == MeetingFieldWritePolicy.MANUAL) {
                throw ApiException.invalidField("fields", "人工字段以飞书为唯一事实来源，不可写入：" + businessCode + "。");
            }
            String feishuField = mapping.get(businessCode);
            if (feishuField == null) {
                throw ApiException.invalidField("fields", "业务字段未映射飞书字段：" + businessCode + "。");
            }
            if (value == null) {
                continue;
            }
            payload.put(feishuField, value);
            if (code.uniqueKey()) {
                businessKey = value.toString();
            }
        }
        if (businessKey == null || businessKey.isBlank()) {
            throw ApiException.invalidField("fields", "必须提供业务唯一键 " + uniqueKey.code() + "。");
        }

        Instant now = clock.instant();
        MeetingSyncOutboxRow row = new MeetingSyncOutboxRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterpriseId);
        row.setBindingId(binding.getId());
        row.setBusinessKey(businessKey);
        row.setCommandId(command.commandId());
        row.setCreateToken(UUID.randomUUID().toString());
        row.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);
        row.setRunId(command.runId());
        row.setPayloadJson(writeJson(payload));
        outbox.enqueue(row, now);
        return new MeetingSyncCommandResult(binding.getId(), businessKey, command.commandId());
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

    /** 读取绑定的业务字段映射，供发布服务在入队前按映射过滤可投递字段。 */
    public Map<String, String> bindingMapping(MeetingBindingRow binding) {
        return readMapping(binding.getFieldMappingJson());
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
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_COMMAND_INVALID", "业务字段无法序列化。", failure);
        }
    }
}
