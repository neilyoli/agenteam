package com.stonewu.agenteam.service.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 会议飞书同步的单轮入口；异常由上层记录并保留投递箱状态供下轮重试。 */
@Service
public class MeetingSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(MeetingSyncWorker.class);
    private final MeetingSyncTransactions transactions;

    public MeetingSyncWorker(MeetingSyncTransactions transactions) {
        this.transactions = transactions;
    }

    public boolean runOnce() {
        try {
            return transactions.runOnce();
        } catch (RuntimeException failure) {
            log.warn("会议飞书同步本轮未完成", failure);
            return false;
        }
    }
}
