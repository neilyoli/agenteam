package com.stonewu.agenteam.configuration.meeting;

import com.stonewu.agenteam.service.meeting.MeetingEventWorker;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 单个有界线程处理会议事件，与定时操作工作进程隔离。 */
@Configuration
@ConditionalOnProperty(name = {"agenteam.maintenance.worker-enabled", "agenteam.meeting.event-worker-enabled"},
    havingValue = "true", matchIfMissing = true)
public class MeetingEventScheduling {
    private final MeetingEventWorker worker;
    private final Semaphore available = new Semaphore(1);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("meeting-event-", 0).factory());

    public MeetingEventScheduling(MeetingEventWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${agenteam.meeting.event-poll-ms:500}", initialDelay = 1000)
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
