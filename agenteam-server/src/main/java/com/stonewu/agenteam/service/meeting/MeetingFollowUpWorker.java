package com.stonewu.agenteam.service.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 会议督办扫描的单轮入口；异常由上层记录并保留作业状态供下轮重试。 */
@Service
public class MeetingFollowUpWorker {
    private static final Logger log = LoggerFactory.getLogger(MeetingFollowUpWorker.class);
    private final MeetingFollowUpTransactions transactions;

    public MeetingFollowUpWorker(MeetingFollowUpTransactions transactions) {
        this.transactions = transactions;
    }

    public boolean runOnce() {
        try {
            return transactions.runOnce();
        } catch (RuntimeException failure) {
            log.warn("会议督办扫描本轮未完成", failure);
            return false;
        }
    }
}
