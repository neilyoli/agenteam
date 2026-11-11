package com.stonewu.agenteam.service.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 会议确认卡片的单轮投递入口；异常由上层记录并保留投递状态供下轮重试。 */
@Service
public class MeetingConfirmationCardWorker {
    private static final Logger log = LoggerFactory.getLogger(MeetingConfirmationCardWorker.class);
    private final MeetingConfirmationDeliveryService delivery;

    public MeetingConfirmationCardWorker(MeetingConfirmationDeliveryService delivery) {
        this.delivery = delivery;
    }

    public boolean runOnce() {
        try {
            return delivery.runOnce();
        } catch (RuntimeException failure) {
            log.warn("会议确认卡片本轮投递未完成", failure);
            return false;
        }
    }
}
