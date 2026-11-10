package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书投递编排：领取后先按唯一键对账 findRecord，未命中才以稳定 create_token 幂等创建，
 * 再以字段级 patch 更新业务字段并回写同步状态；异常按可重试与阻断分类。
 */
@Service
public class MeetingSyncTransactions {
    private static final Logger log = LoggerFactory.getLogger(MeetingSyncTransactions.class);
    private static final int BATCH = 50;

    private final MeetingSyncOutbox outbox;
    private final MeetingBindingMapper bindings;
    private final IntegrationQueryService integrations;
    private final IntegrationTokenService tokens;
    private final MeetingBitableClient bitable;
    private final ObjectMapper json;
    private final Clock clock;

    public MeetingSyncTransactions(MeetingSyncOutbox outbox, MeetingBindingMapper bindings,
                                   IntegrationQueryService integrations, IntegrationTokenService tokens,
                                   MeetingBitableClient bitable, ObjectMapper json, Clock clock) {
        this.outbox = outbox;
        this.bindings = bindings;
        this.integrations = integrations;
        this.tokens = tokens;
        this.bitable = bitable;
        this.json = json;
        this.clock = clock;
    }

    public boolean runOnce() {
        Instant now = clock.instant();
        outbox.markExhausted(now);
        List<MeetingSyncOutboxRow> due = outbox.due(now, BATCH);
        if (due.isEmpty()) {
            return false;
        }
        for (MeetingSyncOutboxRow row : due) {
            outbox.claim(row.getBindingId(), row.getBusinessKey(), now)
                .ifPresent(attempt -> deliver(attempt, now));
        }
        return true;
    }

    private void deliver(MeetingSyncOutbox.Attempt attempt, Instant now) {
        MeetingSyncOutboxRow row = attempt.row();
        MeetingBindingRow binding = loadBinding(row.getEnterpriseId(), row.getBindingId());
        if (binding == null || !"enabled".equals(binding.getStatus())) {
            outbox.block(attempt, "MEETING_BINDING_UNAVAILABLE", null, now);
            return;
        }
        ChannelAccessToken token;
        try {
            var integration = integrations.require(row.getEnterpriseId(), binding.getIntegrationId());
            token = tokens.get(integrations.application(integration));
        } catch (ChannelProviderException failure) {
            outbox.fail(attempt, "CHANNEL_TOKEN_UNAVAILABLE", null, now);
            return;
        }
        String connection = binding.getIntegrationId();
        try {
            String recordId = row.getRecordId();
            if (recordId == null) {
                recordId = bitable.findRecordId(connection, token.value(), binding.getAppToken(),
                    binding.getTableId(), binding.getKeyField(), row.getBusinessKey());
                if (recordId == null) {
                    recordId = bitable.createRecord(connection, token.value(), binding.getAppToken(),
                        binding.getTableId(), row.getCreateToken(),
                        Map.of(binding.getKeyField(), row.getBusinessKey()));
                }
                outbox.bindRecord(attempt, recordId, now);
            }
            Map<String, Object> fields = readPayload(row.getPayloadJson());
            applyWriteback(fields, binding.getWritebackJson(), row.getDesiredVersion(), now);
            bitable.updateRecord(connection, token.value(), binding.getAppToken(),
                binding.getTableId(), recordId, fields);
            outbox.complete(attempt, now);
        } catch (MeetingBitableException failure) {
            if (failure.retryable()) {
                outbox.fail(attempt, failure.feishuCode(), failure.requestId(), now);
            } else {
                outbox.block(attempt, failure.feishuCode(), failure.requestId(), now);
            }
        } catch (ChannelProviderException failure) {
            outbox.fail(attempt, "CHANNEL_UNAVAILABLE", null, now);
        } catch (RuntimeException failure) {
            log.warn("会议飞书投递未完成，绑定={}，业务键={}", row.getBindingId(), row.getBusinessKey(), failure);
            outbox.fail(attempt, "SYNC_FAILED", null, now);
        }
    }

    private MeetingBindingRow loadBinding(String enterpriseId, String bindingId) {
        return bindings.selectOne(new LambdaQueryWrapper<MeetingBindingRow>()
            .eq(MeetingBindingRow::getEnterpriseId, enterpriseId)
            .eq(MeetingBindingRow::getId, bindingId));
    }

    private void applyWriteback(Map<String, Object> fields, String writebackJson, long version, Instant now) {
        Map<String, String> writeback = readMap(writebackJson);
        if (writeback == null) {
            return;
        }
        writeback.forEach((resultKey, feishuField) -> {
            MeetingWritebackResult result = MeetingWritebackResult.of(resultKey);
            if (result == null) {
                return;
            }
            switch (result) {
                case SYNC_STATUS -> fields.put(feishuField, "synced");
                case SYNC_VERSION -> fields.put(feishuField, version);
                case SYNC_TIME -> fields.put(feishuField, now.toEpochMilli());
            }
        });
    }

    private Map<String, Object> readPayload(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("待投递快照无法读取", failure);
        }
    }

    private Map<String, String> readMap(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return json.readValue(value, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("已保存的同步配置无法读取", failure);
        }
    }
}
