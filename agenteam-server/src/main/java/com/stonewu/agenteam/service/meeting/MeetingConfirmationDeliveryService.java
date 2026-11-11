package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingConfirmationMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingTaskIdentityMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingConfirmationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskIdentityRow;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 确认卡片的持久化投递编排。对账扫描只在任务 Outbox 确认 delivered 之后创建或推进
 * 确认轮次（卡片失败不回滚任务同步）；卡片发送拥有独立租约、退避与结果恢复，
 * 命令编号按绑定+任务+版本稳定生成，重试复用同一编号，不重复创建有效确认轮次。
 */
@Service
public class MeetingConfirmationDeliveryService {
    private static final Logger LOG = LoggerFactory.getLogger(MeetingConfirmationDeliveryService.class);
    private static final Duration CARD_LEASE = Duration.ofSeconds(60);
    private static final int RECONCILE_BATCH = 50;
    private static final int SEND_BATCH = 20;
    private static final int CARD_MAX_ATTEMPTS = 6;

    private final MeetingConfirmationMapper confirmations;
    private final MeetingTaskIdentityMapper identities;
    private final MeetingBindingMapper bindings;
    private final MeetingConfirmationCardSender cards;
    private final ChatIdentityService channelIdentity;
    private final Clock clock;

    public MeetingConfirmationDeliveryService(MeetingConfirmationMapper confirmations,
                                              MeetingTaskIdentityMapper identities,
                                              MeetingBindingMapper bindings,
                                              MeetingConfirmationCardSender cards,
                                              ChatIdentityService channelIdentity, Clock clock) {
        this.confirmations = confirmations;
        this.identities = identities;
        this.bindings = bindings;
        this.cards = cards;
        this.channelIdentity = channelIdentity;
        this.clock = clock;
    }

    /** 单轮投递：先对账补建确认轮次，再领取到期卡片并投递。任何单条失败不影响其余。 */
    public boolean runOnce() {
        Instant now = clock.instant();
        boolean worked = reconcile(now);
        for (MeetingConfirmationRow row : confirmations.dueCardSends(now, SEND_BATCH)) {
            String token = UUID.randomUUID().toString();
            if (confirmations.claimCardSend(row.getEnterpriseId(), row.getId(), token,
                Timestamp.from(now.plus(CARD_LEASE)), Timestamp.from(now)) == 0) {
                continue;
            }
            worked = true;
            deliver(row.getEnterpriseId(), row.getId(), token, now);
        }
        return worked;
    }

    /** 对账：为已确认 delivered 的任务版本创建或推进确认轮次；只写数据库，不发网络请求。 */
    private boolean reconcile(Instant now) {
        List<MeetingSyncOutboxRow> due = confirmations.deliveredTasksAwaitingConfirmation(RECONCILE_BATCH);
        if (due.isEmpty()) {
            return false;
        }
        for (MeetingSyncOutboxRow outbox : due) {
            try {
                onTaskDelivered(outbox, now);
            } catch (RuntimeException failure) {
                LOG.warn("会议确认轮次对账未完成，绑定={}，业务键={}", outbox.getBindingId(),
                    outbox.getBusinessKey(), failure);
            }
        }
        return true;
    }

    /** 任务投递成功后开启（或推进）确认轮次；负责人经可信身份解析，未解析则不发卡。 */
    private void onTaskDelivered(MeetingSyncOutboxRow outbox, Instant now) {
        String enterpriseId = outbox.getEnterpriseId();
        String businessKey = outbox.getBusinessKey();
        long deliveredVersion = outbox.getDeliveredVersion();
        var existing = confirmations.find(enterpriseId, outbox.getBindingId(), businessKey);
        if (existing != null && existing.getTaskVersion() != null
            && existing.getTaskVersion() >= deliveredVersion) {
            return;
        }
        MeetingTaskIdentityRow identity = identities.find(enterpriseId, businessKey);
        if (identity == null) {
            LOG.warn("会议任务缺少身份记录，跳过确认轮次，业务键={}", businessKey);
            return;
        }
        MeetingBindingRow binding = bindings.selectById(outbox.getBindingId());
        if (binding == null) {
            LOG.warn("会议任务绑定已不存在，跳过确认轮次，绑定={}", outbox.getBindingId());
            return;
        }
        // 可信身份解析：卡片只发给当前企业、当前飞书接入解析出的负责人，歧义或未绑定不发卡。
        String ownerSubject = null;
        String ownerUserId = null;
        if (identity.getOwnerOpenId() != null) {
            var resolved = channelIdentity.find(enterpriseId, binding.getIntegrationId(), "feishu_open_id",
                identity.getOwnerOpenId());
            if (resolved.isPresent()) {
                ownerSubject = identity.getOwnerOpenId();
                ownerUserId = resolved.get().getUserId();
            }
        }
        boolean unresolved = ownerSubject == null;
        String cardTitle = "会议任务确认：" + identity.getTitleCurrent();
        String cardDescription = "负责人：" + (identity.getOwnerName() == null ? identity.getOwnerOpenId()
            : identity.getOwnerName()) + "；来源会议：" + identity.getMeetingId()
            + "。请确认是否接受该任务；需要调整时请填写意见。";
        String cardCommandId = "card:" + outbox.getBindingId() + ":" + businessKey + ":" + deliveredVersion;
        String cardStatus = unresolved ? "unresolved" : "pending";
        String roundStatus = unresolved ? MeetingConfirmationState.UNRESOLVED.value()
            : MeetingConfirmationState.PENDING.value();
        if (existing == null) {
            MeetingConfirmationRow row = new MeetingConfirmationRow();
            row.setId(UUID.randomUUID().toString());
            row.setEnterpriseId(enterpriseId);
            row.setRevision(1L);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            row.setBindingId(outbox.getBindingId());
            row.setBusinessKey(businessKey);
            row.setTaskVersion(deliveredVersion);
            row.setOwnerUserId(ownerUserId);
            row.setOwnerSubject(ownerSubject);
            row.setStatus(roundStatus);
            row.setCardCommandId(cardCommandId);
            row.setCardStatus(cardStatus);
            row.setCardTitle(truncate(cardTitle, 200));
            row.setCardDescription(truncate(cardDescription, 500));
            row.setCardAttemptCount(0);
            row.setCardMaxAttempts(CARD_MAX_ATTEMPTS);
            row.setCardNextAttemptAt(now);
            row.setRunId(outbox.getRunId());
            row.setConfigJson("{}");
            confirmations.insert(row);
            return;
        }
        confirmations.beginRound(enterpriseId, outbox.getBindingId(), businessKey, deliveredVersion,
            ownerUserId, ownerSubject, roundStatus, cardStatus, truncate(cardTitle, 200),
            truncate(cardDescription, 500), cardCommandId, Timestamp.from(now));
    }

    private void deliver(String enterpriseId, String id, String token, Instant now) {
        var row = confirmations.selectById(id);
        if (row == null || !row.getStatus().equals("pending") || row.getCardStatus() == null
            || !row.getCardStatus().equals("sending")) {
            return;
        }
        if (row.getOwnerSubject() == null || row.getOwnerSubject().isBlank()) {
            confirmations.failCardSend(enterpriseId, id, token, Timestamp.from(now), "blocked",
                "负责人未解析或未绑定，不发送确认卡片。", Timestamp.from(now));
            return;
        }
        MeetingBindingRow binding = bindings.selectById(row.getBindingId());
        if (binding == null || !"enabled".equals(binding.getStatus())) {
            confirmations.failCardSend(enterpriseId, id, token, Timestamp.from(now), "blocked",
                "任务绑定不可用，确认卡片暂停投递。", Timestamp.from(now));
            return;
        }
        var card = MeetingConfirmationCard.build(row.getBindingId(), row.getBusinessKey(),
            row.getTaskVersion(), row.getCardTitle(), row.getCardDescription());
        try {
            String messageId = cards.send(enterpriseId, binding.getIntegrationId(), row.getOwnerSubject(), card,
                row.getCardCommandId());
            confirmations.completeCardSend(enterpriseId, id, token, messageId, Timestamp.from(now));
        } catch (MeetingCardDeliveryException failure) {
            handleDeliveryFailure(enterpriseId, id, token, row, failure, now);
        }
    }

    /** 明确拒绝按可重试分类退避；结果不明转 unknown 停止自动重发，交由人工核查。 */
    private void handleDeliveryFailure(String enterpriseId, String id, String token, MeetingConfirmationRow row,
                                       MeetingCardDeliveryException failure, Instant now) {
        if (failure.outcome() == MeetingCardDeliveryException.Outcome.UNKNOWN) {
            LOG.warn("确认卡片投递结果不明，已停止自动重发，任务={}，原因={}", row.getBusinessKey(),
                failure.getMessage());
            confirmations.unknownCardSend(enterpriseId, id, token, truncate(failure.getMessage(), 200),
                Timestamp.from(now));
            return;
        }
        if (!failure.retryable()) {
            confirmations.failCardSend(enterpriseId, id, token, Timestamp.from(now), "blocked",
                truncate(failure.getMessage(), 200), Timestamp.from(now));
            return;
        }
        String status = row.getCardAttemptCount() >= row.getCardMaxAttempts() ? "blocked" : "failed";
        confirmations.failCardSend(enterpriseId, id, token,
            Timestamp.from(backoff(now, row.getCardAttemptCount())), status,
            truncate(failure.getMessage(), 200), Timestamp.from(now));
    }

    /** 失败退避：min(300, 5 &lt;&lt; min(6, attempts-1)) 秒并叠加抖动。 */
    static Instant backoff(Instant now, int attemptCount) {
        int index = Math.min(6, Math.max(0, attemptCount - 1));
        long seconds = Math.min(300, 5L << index);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, seconds / 2));
        return now.plusSeconds(seconds + jitter);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
