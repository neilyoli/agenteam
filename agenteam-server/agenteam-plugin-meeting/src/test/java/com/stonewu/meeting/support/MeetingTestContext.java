package com.stonewu.meeting.support;

import com.stonewu.plugin.api.JavaPluginContext;
import com.stonewu.tool.api.ToolCancellation;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用调用上下文：可注入或不注入凭据，按次数模拟取消；beforeSend 仅做取消校验。
 */
public final class MeetingTestContext implements JavaPluginContext, ToolCancellation {
    private final String credential;
    private final AtomicInteger checks = new AtomicInteger();
    private final int cancelAfter;

    public MeetingTestContext(String credential, int cancelAfter) {
        this.credential = credential;
        this.cancelAfter = cancelAfter;
    }

    public static MeetingTestContext withCredential() {
        return new MeetingTestContext("Bearer test-access-token", Integer.MAX_VALUE);
    }

    public static MeetingTestContext withoutCredential() {
        return new MeetingTestContext(null, Integer.MAX_VALUE);
    }

    public static MeetingTestContext cancellingAfter(int checks) {
        return new MeetingTestContext("Bearer test-access-token", checks);
    }

    public AtomicInteger checks() {
        return checks;
    }

    @Override
    public String enterpriseId() {
        return "enterprise-meeting-test";
    }

    @Override
    public String actorId() {
        return "actor-meeting-test";
    }

    @Override
    public String runId() {
        return "run-meeting-test";
    }

    @Override
    public String operationId() {
        return "operation-meeting-test";
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(10);
    }

    @Override
    public ToolCancellation cancellation() {
        return this;
    }

    @Override
    public void requireActive() {
        if (checks.incrementAndGet() >= cancelAfter) {
            throw new IllegalStateException("测试已取消");
        }
    }

    @Override
    public <T extends AutoCloseable> T track(T resource) {
        return resource;
    }

    @Override
    public void beforeSend() {
        requireActive();
    }

    @Override
    public Map<String, String> credentialHeaders() {
        if (credential == null) {
            return Map.of();
        }
        return Map.of("Authorization", credential);
    }
}
