package com.stonewu.agenteam.configuration.meeting;

import com.stonewu.agenteam.service.meeting.MeetingSyncWorker;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 单个有界线程处理会议飞书投递，与事件处理工作进程隔离。 */
@Configuration
@ConditionalOnProperty(name = {"agenteam.maintenance.worker-enabled", "agenteam.meeting.sync-worker-enabled"},
    havingValue = "true", matchIfMissing = true)
public class MeetingSyncScheduling {
    private final MeetingSyncWorker worker;
    private final Semaphore available = new Semaphore(1);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("meeting-sync-", 0).factory());

    public MeetingSyncScheduling(MeetingSyncWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${agenteam.meeting.sync-poll-ms:2000}", initialDelay = 5000)
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
