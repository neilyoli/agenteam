package com.stonewu.meeting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.meeting.MeetingError;
import com.stonewu.meeting.api.MeetingApiClient;
import com.stonewu.meeting.domain.Meeting;
import com.stonewu.meeting.domain.MeetingArtifact;
import com.stonewu.meeting.domain.MeetingParticipant;
import com.stonewu.meeting.domain.MeetingSearchPage;
import com.stonewu.plugin.api.JavaPluginContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会议资料读取服务：严格按飞书 VC Open API 契约转换时间、分页和会议数据。
 * 身份与资源范围由宿主凭据决定，本服务不接收模型指定的 app_token / table_id / 越权身份。
 */
public final class MeetingReadService {
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_QUOTE_LENGTH = 2000;
    private static final Duration MAX_SEARCH_RANGE = Duration.ofDays(90);

    private final MeetingApiClient client;

    public MeetingReadService(MeetingApiClient client) {
        this.client = client;
    }

    /**
     * 飞书只提供按会议号查询会议实例的列表接口。时间范围最多 90 天，API 参数使用 Unix 秒。
     * 未传时间范围时默认查询最近 90 天。
     */
    public MeetingSearchPage search(String startTime, String endTime, String meetingNo,
                                    String pageToken, int pageSize, JavaPluginContext context) {
        requireMeetingNo(meetingNo);
        TimeRange range = timeRange(startTime, endTime, Instant.now());
        String query = "meeting_no=" + encode(meetingNo)
            + "&start_time=" + range.start().getEpochSecond()
            + "&end_time=" + range.end().getEpochSecond()
            + "&page_size=" + pageSize;
        if (notBlank(pageToken)) {
            query += "&page_token=" + encode(pageToken);
        }

        JsonNode data = client.searchMeetings(query, context);
        return new MeetingSearchPage(
            mapMeetingBriefs(data.path("meeting_briefs")),
            data.path("has_more").asBoolean(false),
            data.path("page_token").asText(null));
    }

    public Meeting get(String meetingId, JavaPluginContext context) {
        requireMeetingId(meetingId);
        JsonNode meeting = client.getMeeting(meetingId, context).path("meeting");
        if (meeting.isMissingNode() || meeting.isNull()) {
            throw new MeetingError("MEETING_NOT_FOUND", "未找到指定会议");
        }
        return mapMeeting(meeting);
    }

    public MeetingArtifact artifact(String meetingId, String minuteToken, JavaPluginContext context) {
        if (notBlank(minuteToken)) {
            return artifactFromToken(minuteToken, context);
        }
        requireMeetingId(meetingId);
        JsonNode meeting = client.getMeeting(meetingId, context).path("meeting");
        String token = meeting.path("minutes_token").asText(null);
        if (token == null || token.isBlank()) {
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
            "page_size=" + pageSize + (notBlank(pageToken) ? "&page_token=" + encode(pageToken) : ""), context);
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
            "page_size=" + pageSize + (notBlank(pageToken) ? "&page_token=" + encode(pageToken) : ""), context);
        return Map.of(
            "minuteToken", minuteToken,
            "hasMore", data.path("has_more").asBoolean(false),
            "pageToken", data.path("page_token").asText(""),
            "utterances", mapUtterances(data.path("utterances")));
    }

    private List<Meeting> mapMeetingBriefs(JsonNode list) {
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<Meeting> result = new ArrayList<>(list.size());
        for (JsonNode item : list) {
            result.add(new Meeting(
                item.path("id").asText(null),
                item.path("meeting_no").asText(null),
                truncate(item.path("topic").asText(null), MAX_TITLE_LENGTH),
                null, null, List.of(), null, null, null, null));
        }
        return result;
    }

    private Meeting mapMeeting(JsonNode meeting) {
        JsonNode host = meeting.path("host_user");
        return new Meeting(
            meeting.path("id").asText(null),
            meeting.path("meeting_no").asText(null),
            truncate(meeting.path("topic").asText(null), MAX_TITLE_LENGTH),
            identifier(host.path("id")),
            firstText(host, "name", "user_name"),
            mapParticipants(meeting.path("participants")),
            instant(meeting.path("start_time")),
            instant(meeting.path("end_time")),
            meeting.path("calendar_type").asText(null),
            meeting.hasNonNull("status") ? meeting.path("status").asInt() :
                meeting.path("meeting_status").isNull() ? null : meeting.path("meeting_status").asInt());
    }

    private List<MeetingParticipant> mapParticipants(JsonNode list) {
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<MeetingParticipant> result = new ArrayList<>(list.size());
        for (JsonNode participant : list) {
            String id = identifier(participant.path("id"));
            result.add(new MeetingParticipant(
                firstText(participant, "open_id") == null ? id : firstText(participant, "open_id"),
                firstText(participant, "union_id"),
                truncate(firstText(participant, "name", "user_name"), 200),
                firstText(participant, "user_id"),
                participant.path("is_host").isBoolean() ? participant.path("is_host").asBoolean() : null,
                participant.path("user_type").isMissingNode() || participant.path("user_type").isNull()
                    ? null : participant.path("user_type").asText()));
        }
        return result;
    }

    private List<Map<String, Object>> mapParagraphs(JsonNode list) {
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (JsonNode paragraph : list) {
            result.add(Map.of(
                "index", paragraph.path("index").asInt(),
                "speaker", truncate(paragraph.path("speaker_name").asText(""), 200),
                "text", truncate(paragraph.path("text").asText(""), MAX_QUOTE_LENGTH),
                "startTime", paragraph.path("start_time").asText("")));
        }
        return result;
    }

    private List<Map<String, Object>> mapUtterances(JsonNode list) {
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (JsonNode utterance : list) {
            result.add(Map.of(
                "index", utterance.path("index").asInt(),
                "speaker", truncate(utterance.path("speaker_name").asText(""), 200),
                "text", truncate(utterance.path("text").asText(""), MAX_QUOTE_LENGTH),
                "startTime", utterance.path("start_time").asText("")));
        }
        return result;
    }

    static TimeRange timeRange(String startTime, String endTime, Instant now) {
        Instant end = notBlank(endTime) ? parseTime(endTime, "endTime") : now;
        Instant start = notBlank(startTime) ? parseTime(startTime, "startTime") : end.minus(MAX_SEARCH_RANGE);
        if (!start.isBefore(end)) {
            throw MeetingError.invalidArgument("会议查询开始时间必须早于结束时间");
        }
        if (Duration.between(start, end).compareTo(MAX_SEARCH_RANGE) > 0) {
            throw MeetingError.invalidArgument("飞书会议列表查询范围不能超过 90 天，请缩小时间范围");
        }
        return new TimeRange(start, end);
    }

    static long toEpochSeconds(String value) {
        return parseTime(value, "时间").getEpochSecond();
    }

    private static Instant parseTime(String value, String field) {
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException failure) {
            throw MeetingError.invalidArgument(field + " 必须使用带时区的 ISO-8601 时间格式");
        }
    }

    private static Instant instant(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value = node.asText();
        if (value.isBlank() || "0".equals(value)) {
            return null;
        }
        try {
            if (value.matches("-?[0-9]+")) {
                return Instant.ofEpochSecond(Long.parseLong(value));
            }
            return Instant.parse(value);
        } catch (Exception failure) {
            return null;
        }
    }

    private static String identifier(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return firstText(node, "open_id", "user_id", "union_id");
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void requireMeetingNo(String meetingNo) {
        if (meetingNo == null || !meetingNo.matches("[0-9]{9}")) {
            throw MeetingError.invalidArgument("飞书会议搜索必须提供 9 位会议号 meetingNo；仅提供日期无法通过该接口检索全部会议");
        }
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

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    record TimeRange(Instant start, Instant end) {
    }
}
