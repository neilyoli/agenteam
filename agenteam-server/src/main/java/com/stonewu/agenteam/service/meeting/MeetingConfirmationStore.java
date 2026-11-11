package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingConfirmationMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingConfirmationRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/** 确认轮次的持久化与企业锁；回答用版本与状态栅栏的原子更新表达。 */
@Service
public class MeetingConfirmationStore {
    private final MeetingConfirmationMapper confirmations;
    private final EnterpriseMapper enterprises;

    public MeetingConfirmationStore(MeetingConfirmationMapper confirmations, EnterpriseMapper enterprises) {
        this.confirmations = confirmations;
        this.enterprises = enterprises;
    }

    @Transactional
    public void insert(MeetingConfirmationRow row) {
        enterprises.lockEnterprise(row.getEnterpriseId()).orElseThrow();
        confirmations.insert(row);
    }

    /** 开始新一轮确认：不存在则插入，存在则重置版本、负责人、状态与整组卡片投递状态。 */
    @Transactional
    public void beginRound(MeetingConfirmationRow row, Instant now) {
        enterprises.lockEnterprise(row.getEnterpriseId()).orElseThrow();
        var existing = confirmations.find(row.getEnterpriseId(), row.getBindingId(), row.getBusinessKey());
        if (existing == null) {
            confirmations.insert(row);
            return;
        }
        confirmations.beginRound(row.getEnterpriseId(), row.getBindingId(), row.getBusinessKey(),
            row.getTaskVersion(), row.getOwnerUserId(), row.getOwnerSubject(), row.getStatus(),
            row.getCardStatus() == null ? "unresolved" : row.getCardStatus(), row.getCardTitle(),
            row.getCardDescription(), row.getCardCommandId(), Timestamp.from(now));
    }

    /** 仅当仍为待确认且版本一致时回答；返回是否成功推进。 */
    @Transactional
    public boolean answer(String enterpriseId, String bindingId, String businessKey, long expectedVersion,
                          MeetingConfirmationAction action, String comment, String eventId, Instant now) {
        enterprises.lockEnterprise(enterpriseId).orElseThrow();
        return confirmations.answer(enterpriseId, bindingId, businessKey, expectedVersion,
            action.state().value(), comment, eventId, Timestamp.from(now)) == 1;
    }

    @Transactional
    public void recordCard(String enterpriseId, String bindingId, String businessKey,
                           String messageId, String commandId, Instant now) {
        confirmations.recordCard(enterpriseId, bindingId, businessKey, messageId, commandId, Timestamp.from(now));
    }

    public MeetingConfirmationRow find(String enterpriseId, String bindingId, String businessKey) {
        return confirmations.find(enterpriseId, bindingId, businessKey);
    }
}
