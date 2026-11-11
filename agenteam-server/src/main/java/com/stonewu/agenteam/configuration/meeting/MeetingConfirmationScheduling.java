package com.stonewu.agenteam.configuration.meeting;

import com.stonewu.agenteam.service.meeting.MeetingConfirmationCardWorker;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 单个有界线程处理会议确认卡片对账与投递，与任务同步和事件处理进程隔离。 */
@Configuration
@ConditionalOnProperty(name = {"agenteam.maintenance.worker-enabled",
    "agenteam.meeting.confirmation-worker-enabled"}, havingValue = "true", matchIfMissing = true)
public class MeetingConfirmationScheduling {
    private final MeetingConfirmationCardWorker worker;
    private final Semaphore available = new Semaphore(1);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("meeting-confirmation-", 0).factory());

    public MeetingConfirmationScheduling(MeetingConfirmationCardWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${agenteam.meeting.confirmation-poll-ms:5000}", initialDelay = 8000)
    public void poll() {
        if (!available.tryAcquire()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    worker.runOnce();
                } finally {
                    available.release();
                }
            });
        } catch (RuntimeException failure) {
            available.release();
            throw failure;
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
