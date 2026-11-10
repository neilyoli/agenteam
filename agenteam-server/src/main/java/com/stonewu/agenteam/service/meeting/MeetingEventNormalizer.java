package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.meeting.entity.NormalizedMeetingEvent;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 识别飞书会议领域事件并提取标识；具体事件类型由订阅声明，飞书类型清单在接入阶段验证。
 */
@Component
public class MeetingEventNormalizer {
    private static final Set<String> PREFIXES = Set.of("meeting.", "minutes.");

    public Optional<NormalizedMeetingEvent> normalize(IntegrationApplication app, JsonNode envelope, Instant receivedAt) {
        var header = envelope.path("header");
        var event = envelope.has("event") ? envelope.path("event") : envelope;
        String type = header.path("event_type").asText(event.path("event_type").asText(""));
        if (type.isBlank() || PREFIXES.stream().noneMatch(type::startsWith)) {
            return Optional.empty();
        }
        verifyScope(app, header);
        verifyScope(app, event);
        String eventId = header.path("event_id").asText(event.path("event_id").asText(""));
        String meetingId = firstText(event.path("meeting").path("meeting_id"), event.path("meeting_id"));
        String minuteToken = firstText(event.path("minutes_token"), event.path("minute_token"));
        return Optional.of(new NormalizedMeetingEvent(type, eventId, meetingId, minuteToken, receivedAt));
    }

    private static String firstText(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            String value = candidate.asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void verifyScope(IntegrationApplication app, JsonNode source) {
        String appId = source.path("app_id").asText("");
        String tenant = source.path("tenant_key").asText("");
        if ((!appId.isBlank() && !appId.equals(app.externalAppId())) || (!tenant.isBlank() && app.externalTenantId() != null
            && !tenant.equals(app.externalTenantId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MEETING_APPLICATION_MISMATCH", "会议事件不属于当前应用或企业。");
        }
    }
}
