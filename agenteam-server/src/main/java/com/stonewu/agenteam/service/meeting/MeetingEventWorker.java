package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.model.background.entity.JobLease;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 会议事件处理使用独立租约；真正的智能体执行由已有工作进程处理。 */
@Service
public class MeetingEventWorker {
    private static final Logger log = LoggerFactory.getLogger(MeetingEventWorker.class);
    private final MeetingEventTransactions transactions;
    private final String worker = "meeting-event-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("meeting-event-lease-", 0).factory());

    public MeetingEventWorker(MeetingEventTransactions transactions) {
        this.transactions = transactions;
    }

    public boolean runOnce() {
        JobLease lease;
        try {
            var claimed = transactions.claim(worker);
            if (claimed.isEmpty()) {
                return false;
            }
            lease = claimed.get();
        } catch (RuntimeException failure) {
            log.warn("会议事件暂时无法领取", failure);
            return false;
        }
        var renewal = heartbeat.scheduleAtFixedRate(() -> renew(lease), 15, 15, TimeUnit.SECONDS);
        try {
            transactions.submit(lease);
        } catch (MeetingEventStore.LeaseLostException failure) {
            log.warn("会议事件处理结果因租约变化未提交，工作编号={}", lease.id(), failure);
        } catch (RuntimeException failure) {
            log.warn("会议事件未处理完成，工作编号={}", lease.id(), failure);
            try {
                transactions.failure(lease, failure);
            } catch (RuntimeException saveFailure) {
                log.warn("会议事件失败状态暂未保存，工作编号={}", lease.id(), saveFailure);
            }
        } finally {
            renewal.cancel(false);
        }
        return true;
    }

    private void renew(JobLease lease) {
        try {
            transactions.renew(lease);
        } catch (RuntimeException failure) {
            log.warn("会议事件工作租约无法续期，工作编号={}", lease.id(), failure);
        }
    }

    @PreDestroy
    public void close() {
        heartbeat.shutdownNow();
    }
}
