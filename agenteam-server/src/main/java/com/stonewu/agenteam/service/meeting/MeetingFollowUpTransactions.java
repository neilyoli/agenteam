package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpScanRow;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import com.stonewu.agenteam.service.meeting.MeetingBitableClient.MeetingRecord;
import com.stonewu.agenteam.service.meeting.MeetingBitableClient.MeetingRecordPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 督办扫描编排：领取扫描作业后读取飞书任务宽表最新状态，按跟进条件识别候选，
 * 经台账 next_reminder_at 原子限频后向负责人催办，失败按可重试与阻断分类。
 */
@Service
public class MeetingFollowUpTransactions {
    private static final Logger log = LoggerFactory.getLogger(MeetingFollowUpTransactions.class);
    private static final int BATCH = 20;
    private static final int PAGE_SIZE = 500;

    private final MeetingFollowUpStore store;
    private final MeetingBindingMapper bindings;
    private final IntegrationQueryService integrations;
    private final IntegrationTokenService tokens;
    private final MeetingBitableClient bitable;
    private final MeetingFollowUpClassifier classifier;
    private final MeetingConfirmationCardSender cards;
    private final ObjectMapper json;
    private final Clock clock;

    public MeetingFollowUpTransactions(MeetingFollowUpStore store, MeetingBindingMapper bindings,
                                       IntegrationQueryService integrations, IntegrationTokenService tokens,
                                       MeetingBitableClient bitable, MeetingFollowUpClassifier classifier,
                                       MeetingConfirmationCardSender cards, ObjectMapper json, Clock clock) {
        this.store = store;
        this.bindings = bindings;
        this.integrations = integrations;
        this.tokens = tokens;
        this.bitable = bitable;
        this.classifier = classifier;
        this.cards = cards;
        this.json = json;
        this.clock = clock;
    }

    public boolean runOnce() {
        Instant now = clock.instant();
        store.markExhausted(now);
        List<MeetingFollowUpScanRow> due = store.due(now, BATCH);
        if (due.isEmpty()) {
            return false;
        }
        for (MeetingFollowUpScanRow scan : due) {
            store.claim(scan.getEnterpriseId(), scan.getId(), now).ifPresent(attempt -> deliver(attempt, now));
        }
        return true;
    }

    private void deliver(MeetingFollowUpStore.Attempt attempt, Instant now) {
        MeetingFollowUpScanRow scan = attempt.row();
        MeetingBindingRow binding = loadBinding(scan.getEnterpriseId(), scan.getBindingId());
        if (binding == null || !"enabled".equals(binding.getStatus())) {
            store.block(attempt, "MEETING_BINDING_UNAVAILABLE", now);
            return;
        }
        MeetingFollowUpPolicy policy;
        try {
            policy = json.readValue(scan.getPolicyJson(), MeetingFollowUpPolicy.class);
        } catch (JsonProcessingException failure) {
            store.block(attempt, "MEETING_POLICY_INVALID", now);
            return;
        }
        ChannelAccessToken token;
        try {
            var integration = integrations.require(scan.getEnterpriseId(), binding.getIntegrationId());
            token = tokens.get(integrations.application(integration));
        } catch (ChannelProviderException failure) {
            store.fail(attempt, "CHANNEL_TOKEN_UNAVAILABLE", now);
            return;
        }
        if (!policy.withinWindow(now)) {
            store.complete(attempt, resultJson("outside_window", 0, 0, 0, 0), now);
            return;
        }
        Map<String, String> mapping = readMapping(binding.getFieldMappingJson());

        List<MeetingRecord> records = new ArrayList<>();
        try {
            String pageToken = null;
            do {
                MeetingRecordPage page = bitable.listRecords(binding.getIntegrationId(), token.value(),
                    binding.getAppToken(), binding.getTableId(), pageToken, PAGE_SIZE);
                records.addAll(page.records());
                if (!page.hasMore()) {
                    break;
                }
                pageToken = page.pageToken();
            } while (pageToken != null && !pageToken.isBlank());
        } catch (MeetingBitableException failure) {
            if (failure.retryable()) {
                store.fail(attempt, failure.feishuCode(), now);
            } else {
                store.block(attempt, failure.feishuCode(), now);
            }
            return;
        }

        int candidates = 0;
        int reminded = 0;
        int skipped = 0;
        int failed = 0;
        for (MeetingRecord record : records) {
            var candidate = classifier.classify(record, mapping, binding.getKeyField(), policy.conditions(), now);
            if (candidate.isEmpty()) {
                continue;
            }
            candidates++;
            String ownerSubject = candidate.get().ownerSubject();
            if (ownerSubject == null || ownerSubject.isBlank()) {
                skipped++;
                continue;
            }
            String evidenceJson = writeEvidence(candidate.get().evidence());
            ensureLedger(scan.getEnterpriseId(), scan.getBindingId(), candidate.get().businessKey(), ownerSubject, now);
            if (!store.remind(scan.getEnterpriseId(), scan.getBindingId(), candidate.get().businessKey(),
                now, now.plus(policy.cooldown()), evidenceJson)) {
                skipped++;
                continue;
            }
            try {
                var card = MeetingFollowUpCard.build(candidate.get().title(), candidate.get().reasons(), candidate.get().evidence());
                cards.send(scan.getEnterpriseId(), binding.getIntegrationId(), ownerSubject, card, UUID.randomUUID().toString());
                store.recordSent(scan.getEnterpriseId(), scan.getBindingId(), candidate.get().businessKey(), now);
                reminded++;
            } catch (ChannelProviderException failure) {
                failed++;
            }
        }
        store.complete(attempt, resultJson("completed", candidates, reminded, skipped, failed), now);
    }

    private void ensureLedger(String enterpriseId, String bindingId, String businessKey, String ownerSubject, Instant now) {
        if (store.find(enterpriseId, bindingId, businessKey) != null) {
            return;
        }
        MeetingFollowUpRow row = new MeetingFollowUpRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterpriseId);
        row.setRevision(1L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setBindingId(bindingId);
        row.setBusinessKey(businessKey);
        row.setOwnerSubject(ownerSubject);
        row.setReminderCount(0);
        row.setStatus("active");
        try {
            store.insertLedger(row);
        } catch (DuplicateKeyException ignored) {
            // 并发下已有台账，限频由 remind 栅栏判定
        }
    }

    private MeetingBindingRow loadBinding(String enterpriseId, String bindingId) {
        return bindings.selectOne(new LambdaQueryWrapper<MeetingBindingRow>()
            .eq(MeetingBindingRow::getEnterpriseId, enterpriseId)
            .eq(MeetingBindingRow::getId, bindingId));
    }

    private String writeEvidence(com.fasterxml.jackson.databind.JsonNode evidence) {
        try {
            return json.writeValueAsString(evidence);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("督办证据无法序列化", failure);
        }
    }

    private String resultJson(String outcome, int candidates, int reminded, int skipped, int failed) {
        ObjectNode node = json.createObjectNode().put("outcome", outcome)
            .put("candidates", candidates).put("reminded", reminded)
            .put("skipped", skipped).put("failed", failed);
        return node.toString();
    }

    private Map<String, String> readMapping(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(value, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (JsonProcessingException failure) {
            log.warn("督办扫描字段映射无法读取", failure);
            return Map.of();
        }
    }
}
