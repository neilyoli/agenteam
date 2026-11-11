package com.stonewu.meeting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.meeting.MeetingError;
import com.stonewu.meeting.api.MeetingApiClient;
import com.stonewu.meeting.domain.Meeting;
import com.stonewu.meeting.domain.MeetingArtifact;
import com.stonewu.meeting.domain.MeetingParticipant;
import com.stonewu.plugin.api.JavaPluginContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会议资料读取服务：把飞书 Open API 响应规范化为领域对象，处理空值、分页与脱敏。
 * 身份与资源范围由宿主凭据决定，本服务不接收模型指定的 app_token / table_id / 越权身份。
 */
public final class MeetingReadService {
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_QUOTE_LENGTH = 2000;

    private final MeetingApiClient client;

    public MeetingReadService(MeetingApiClient client) {
        this.client = client;
    }

    public List<Meeting> search(String startTime, String endTime, String meetingNo,
                                String pageToken, int pageSize, JavaPluginContext context) {
        StringBuilder query = new StringBuilder("page_size=").append(pageSize);
        if (notBlank(startTime)) {
            query.append("&start_time=").append(startTime);
        }
        if (notBlank(endTime)) {
            query.append("&end_time=").append(endTime);
        }
        if (notBlank(meetingNo)) {
            query.append("&meeting_no=").append(meetingNo);
        }
        if (notBlank(pageToken)) {
            query.append("&page_token=").append(pageToken);
        }
        JsonNode data = client.searchMeetings(query.toString(), context);
        return mapMeetings(data.path("meeting_list"));
    }

    public Meeting get(String meetingId, JavaPluginContext context) {
        requireMeetingId(meetingId);
        JsonNode meeting = client.getMeeting(meetingId, context).path("meeting");
        if (meeting.isMissingNode() || meeting.isNull()) {
            throw new MeetingError("MEETING_NOT_FOUND", "未找到指定会议");
        }
        return mapMeeting(meeting);
    }

    public List<MeetingParticipant> participants(String meetingId, String pageToken, int pageSize,
                                                 JavaPluginContext context) {
        requireMeetingId(meetingId);
        String query = "page_size=" + pageSize + (notBlank(pageToken) ? "&page_token=" + pageToken : "");
        JsonNode data = client.getParticipants(meetingId, query, context);
        return mapParticipants(data.path("participants"));
    }

    public MeetingArtifact artifact(String meetingId, String minuteToken, JavaPluginContext context) {
        if (notBlank(minuteToken)) {
            return artifactFromToken(minuteToken, context);
        }
        requireMeetingId(meetingId);
        JsonNode meeting = client.getMeeting(meetingId, context).path("meeting");
        String token = meeting.path("minutes_token").asText(null);
        if (token == null) {
            return new MeetingArtifact(null, false, false, false, null, null, null);
        }
        return artifactFromToken(token, context);
    }

    private MeetingArtifact artifactFromToken(String minuteToken, JavaPluginContext context) {
        JsonNode meta = client.getMinutesMeta(minuteToken, context);
        return new MeetingArtifact(
            minuteToken,
            meta.path("minutes_ready").asBoolean(false),
            meta.path("transcript_ready").asBoolean(false),
            meta.path("note_ready").asBoolean(false),
            meta.path("note_url").asText(null),
            meta.path("minutes_word_count").isNull() ? null : meta.path("minutes_word_count").asInt(),
            meta.path("transcript_word_count").isNull() ? null : meta.path("transcript_word_count").asInt());
    }

    public Map<String, Object> minutes(String minuteToken, String pageToken, int pageSize,
                                       JavaPluginContext context) {
        requireMinuteToken(minuteToken);
        JsonNode data = client.getMinutes(minuteToken,
            "page_size=" + pageSize + (notBlank(pageToken) ? "&page_token=" + pageToken : ""), context);
        return Map.of(
            "minuteToken", minuteToken,
            "hasMore", data.path("has_more").asBoolean(false),
            "pageToken", data.path("page_token").asText(""),
            "paragraphs", mapParagraphs(data.path("paragraphs")));
    }

    public Map<String, Object> transcript(String minuteToken, String pageToken, int pageSize,
                                          JavaPluginContext context) {
        requireMinuteToken(minuteToken);
        JsonNode data = client.getTranscript(minuteToken,
            "page_size=" + pageSize + (notBlank(pageToken) ? "&page_token=" + pageToken : ""), context);
        return Map.of(
            "minuteToken", minuteToken,
            "hasMore", data.path("has_more").asBoolean(false),
            "pageToken", data.path("page_token").asText(""),
            "utterances", mapUtterances(data.path("utterances")));
    }

    private List<Meeting> mapMeetings(JsonNode list) {
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<Meeting> result = new ArrayList<>(list.size());
        for (JsonNode item : list) {
            JsonNode meeting = item.has("meeting") ? item.path("meeting") : item;
            result.add(mapMeeting(meeting));
        }
        return result;
    }

    private Meeting mapMeeting(JsonNode m) {
        return new Meeting(
            m.path("id").asText(null),
            m.path("meeting_no").asText(null),
            truncate(m.path("topic").asText(null), MAX_TITLE_LENGTH),
            m.path("host_user").path("id").asText(null),
            m.path("host_user").path("user_name").asText(null),
            null,
            instant(m.path("start_time")),
            instant(m.path("end_time")),
            m.path("calendar_type").asText(null),
            m.path("meeting_status").isNull() ? null : m.path("meeting_status").asInt());
    }

    private List<MeetingParticipant> mapParticipants(JsonNode list) {
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<MeetingParticipant> result = new ArrayList<>(list.size());
        for (JsonNode p : list) {
            result.add(new MeetingParticipant(
                p.path("open_id").asText(null),
                p.path("union_id").asText(null),
                truncate(p.path("name").asText(null), 200),
                p.path("user_id").asText(null),
                p.path("is_host").isNull() ? null : p.path("is_host").asBoolean(),
                p.path("user_type").asText(null)));
        }
        return result;
    }

    private List<Map<String, Object>> mapParagraphs(JsonNode list) {
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (JsonNode p : list) {
            result.add(Map.of(
                "index", p.path("index").asInt(),
                "speaker", truncate(p.path("speaker_name").asText(""), 200),
                "text", truncate(p.path("text").asText(""), MAX_QUOTE_LENGTH),
                "startTime", p.path("start_time").asText("")));
        }
        return result;
    }

    private List<Map<String, Object>> mapUtterances(JsonNode list) {
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (JsonNode u : list) {
            result.add(Map.of(
                "index", u.path("index").asInt(),
                "speaker", truncate(u.path("speaker_name").asText(""), 200),
                "text", truncate(u.path("text").asText(""), MAX_QUOTE_LENGTH),
                "startTime", u.path("start_time").asText("")));
        }
        return result;
    }

    private static Instant instant(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText();
        if (text.isBlank() || "0".equals(text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void requireMeetingId(String meetingId) {
        if (meetingId == null || meetingId.isBlank()) {
            throw MeetingError.invalidArgument("缺少会议编号 meeting_id");
        }
    }

    private static void requireMinuteToken(String minuteToken) {
        if (minuteToken == null || minuteToken.isBlank()) {
            throw MeetingError.invalidArgument("缺少妙记编号 minute_token");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
