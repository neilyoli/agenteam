package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingEventMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSubscriptionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 会议事件先落收件箱再触发；重复事件按事件编号去重，未订阅的会议事件不持久保存正文。
 */
@Service
public class MeetingEventIngressService {
    private final MeetingEventNormalizer normalizer;
    private final MeetingSubscriptionMapper subscriptions;
    private final MeetingEventMapper events;
    private final MeetingEventStore store;
    private final EnterpriseMapper enterprises;
    private final BackgroundJobMapper jobs;
    private final ResourceJson json;
    private final Clock clock;

    public MeetingEventIngressService(MeetingEventNormalizer normalizer, MeetingSubscriptionMapper subscriptions,
                                      MeetingEventMapper events, MeetingEventStore store, EnterpriseMapper enterprises,
                                      BackgroundJobMapper jobs, ResourceJson json, Clock clock) {
        this.normalizer = normalizer;
        this.subscriptions = subscriptions;
        this.events = events;
        this.store = store;
        this.enterprises = enterprises;
        this.jobs = jobs;
        this.json = json;
        this.clock = clock;
    }

    /** 返回 true 表示该事件已由会议入口消费，聊天入口不再处理。 */
    @Transactional
    public boolean accept(String enterprise, IntegrationApplication app, JsonNode envelope) {
        var normalized = normalizer.normalize(app, envelope, clock.instant());
        if (normalized.isEmpty()) {
            return false;
        }
        var event = normalized.get();
        enterprises.lockEnterprise(enterprise).orElseThrow();
        // 资料就绪事件（minutes.* 携带 minute_token）快进等待中的会议事件，不单独落新收件箱。
        if (event.minuteToken() != null && !event.minuteToken().isBlank() && event.meetingId() != null
            && fastForward(enterprise, app.id(), event.meetingId(), event.minuteToken())) {
            return true;
        }
        if (!subscribed(enterprise, app.id(), event.eventType())) {
            return true;
        }
        var existing = events.selectOne(new LambdaQueryWrapper<MeetingEventRow>()
            .eq(MeetingEventRow::getEnterpriseId, enterprise).eq(MeetingEventRow::getIntegrationId, app.id())
            .eq(MeetingEventRow::getEventId, event.eventId()));
        if (existing != null) {
            return true;
        }
        var now = clock.instant();
        var row = new MeetingEventRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setRevision(1L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setIntegrationId(app.id());
        row.setEventId(event.eventId());
        row.setEventType(event.eventType());
        row.setMeetingId(event.meetingId());
        row.setMinuteToken(event.minuteToken());
        row.setPayloadJson(json.write(json.tree(Map.of())));
        row.setStatus("received");
        String jobId = UUID.randomUUID().toString();
        jobs.enqueue(jobId, enterprise, null, "meeting_event",
            "meeting-event:" + enterprise + ":" + app.id() + ":" + event.eventId(),
            json.write(json.tree(Map.of("meetingEventId", row.getId()))), now);
        jobs.setAttemptLimit(jobId, MeetingEventTransactions.ARTIFACT_WAIT_MAX_ATTEMPTS);
        row.setJobId(jobId);
        events.insert(row);
        return true;
    }

    /** 把资料就绪回填到等待中的会议事件并把其原任务置为可领取，实现资料就绪后自动续跑。 */
    private boolean fastForward(String enterprise, String integrationId, String meetingId, String minuteToken) {
        var waiting = store.waitingForMeeting(enterprise, integrationId, meetingId);
        if (waiting == null || waiting.getJobId() == null) {
            return false;
        }
        store.requeue(waiting, minuteToken);
        jobs.release(waiting.getJobId(), clock.instant());
        return true;
    }

    private boolean subscribed(String enterprise, String integrationId, String eventType) {
        return subscriptions.selectCount(new LambdaQueryWrapper<MeetingSubscriptionRow>()
            .eq(MeetingSubscriptionRow::getEnterpriseId, enterprise)
            .eq(MeetingSubscriptionRow::getIntegrationId, integrationId)
            .eq(MeetingSubscriptionRow::getEventType, eventType).eq(MeetingSubscriptionRow::getStatus, "enabled")) > 0;
    }
}
