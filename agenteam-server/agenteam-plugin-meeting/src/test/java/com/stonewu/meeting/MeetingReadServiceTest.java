package com.stonewu.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.meeting.api.MeetingApiClient;
import com.stonewu.meeting.api.MeetingApiException;
import com.stonewu.meeting.service.MeetingReadService;
import com.stonewu.meeting.support.MeetingTestContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MeetingReadServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private FeishuServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void mapsSearchResults() {
        startServer(target -> target.startsWith("/open-apis/vc/v1/meetings")
            ? "{\"code\":0,\"data\":{\"meeting_list\":[{\"meeting\":{\"id\":\"m1\",\"meeting_no\":\"M-001\","
                + "\"topic\":\"Q4 对账\",\"host_user\":{\"id\":\"ou_host\",\"user_name\":\"王主持\"},"
                + "\"start_time\":\"2026-11-01T09:00:00Z\",\"end_time\":\"2026-11-01T10:00:00Z\","
                + "\"calendar_type\":\"vc\",\"meeting_status\":2}}]}}"
            : null);
        var meetings = read().search(null, null, null, null, 20, MeetingTestContext.withCredential());
        assertEquals(1, meetings.size());
        assertEquals("Q4 对账", meetings.get(0).topic());
        assertEquals("ou_host", meetings.get(0).hostOpenId());
        assertNotNull(meetings.get(0).startTime());
    }

    @Test
    void mapsMeetingAndParticipants() {
        startServer(target -> {
            if (target.equals("/open-apis/vc/v1/meetings/m1")) {
                return "{\"code\":0,\"data\":{\"meeting\":{\"id\":\"m1\",\"meeting_no\":\"M-001\",\"topic\":\"Q4 对账\","
                    + "\"minutes_token\":\"ob_123\",\"host_user\":{\"id\":\"ou_host\",\"user_name\":\"王主持\"},"
                    + "\"start_time\":\"2026-11-01T09:00:00Z\",\"end_time\":\"2026-11-01T10:00:00Z\"}}}";
            }
            if (target.startsWith("/open-apis/vc/v1/meetings/m1/participant_list")) {
                return "{\"code\":0,\"data\":{\"participants\":[{\"open_id\":\"ou_1\",\"union_id\":\"on_1\","
                    + "\"name\":\"张三\",\"user_id\":\"u1\",\"is_host\":false,\"user_type\":1}]}}";
            }
            return null;
        });
        var ctx = MeetingTestContext.withCredential();
        var meeting = read().get("m1", ctx);
        assertEquals("m1", meeting.meetingId());
        assertEquals("Q4 对账", meeting.topic());
        var participants = read().participants("m1", null, 100, ctx);
        assertEquals(1, participants.size());
        assertEquals("张三", participants.get(0).name());
    }

    @Test
    void resolvesArtifactReadyStateFromToken() {
        startServer(target -> target.startsWith("/open-apis/minutes/v1/minutes/ob_123")
            && !target.contains("page_size")
            ? "{\"code\":0,\"data\":{\"minutes_ready\":true,\"transcript_ready\":true,\"note_ready\":false,"
                + "\"note_url\":\"https://example.note\",\"minutes_word_count\":1200,\"transcript_word_count\":3400}}"
            : null);
        var artifact = read().artifact(null, "ob_123", MeetingTestContext.withCredential());
        assertTrue(artifact.minutesReady());
        assertTrue(artifact.transcriptReady());
        assertTrue(artifact.anyReady());
    }

    @Test
    void reportsArtifactNotReadyWithoutToken() {
        startServer(target -> "{\"code\":0,\"data\":{\"meeting\":{\"id\":\"m1\",\"topic\":\"无妙记\"}}}");
        var artifact = read().artifact("m1", null, MeetingTestContext.withCredential());
        assertFalse(artifact.anyReady());
        assertNull(artifact.minuteToken());
    }

    @Test
    void paginatesMinutesAndTruncatesLongParagraphs() {
        String longText = "纪要原文。".repeat(2000);
        startServer(target -> target.startsWith("/open-apis/minutes/v1/minutes/ob_123") && target.contains("page_size")
            ? "{\"code\":0,\"data\":{\"has_more\":true,\"page_token\":\"next\",\"paragraphs\":[{\"index\":0,"
                + "\"speaker_name\":\"张三\",\"text\":\"" + longText + "\",\"start_time\":\"2026-11-01T09:01:00Z\"}]}}"
            : null);
        var result = read().minutes("ob_123", null, 20, MeetingTestContext.withCredential());
        assertTrue((Boolean) result.get("hasMore"));
        assertEquals("next", result.get("pageToken"));
        var paragraphs = (java.util.List<?>) result.get("paragraphs");
        assertEquals(1, paragraphs.size());
        String text = (String) ((Map<?, ?>) paragraphs.get(0)).get("text");
        assertTrue(text.length() <= 2000);
    }

    @Test
    void paginatesTranscript() {
        startServer(target -> target.startsWith("/open-apis/minutes/v1/minutes/ob_123/transcript")
            ? "{\"code\":0,\"data\":{\"has_more\":false,\"page_token\":\"\",\"utterances\":[{\"index\":0,"
                + "\"speaker_name\":\"张三\",\"text\":\"逐字稿语句\",\"start_time\":\"2026-11-01T09:01:00Z\"}]}}"
            : null);
        var result = read().transcript("ob_123", null, 20, MeetingTestContext.withCredential());
        assertFalse((Boolean) result.get("hasMore"));
        var utterances = (java.util.List<?>) result.get("utterances");
        assertEquals("逐字稿语句", ((Map<?, ?>) utterances.get(0)).get("text"));
    }

    @Test
    void sendsCredentialAsAuthorizationHeader() {
        startServer(target -> "{\"code\":0,\"data\":{\"meeting_list\":[]}}");
        read().search(null, null, null, null, 20, MeetingTestContext.withCredential());
        assertEquals("Bearer test-access-token", server.auth.get());
    }

    @Test
    void classifiesMissingCredentialAsAuthExpired() {
        startServer(target -> "{\"code\":0,\"data\":{}}");
        var e = assertThrows(MeetingApiException.class,
            () -> read().search(null, null, null, null, 20, MeetingTestContext.withoutCredential()));
        assertEquals(MeetingApiException.Kind.AUTH_EXPIRED, e.kind());
    }

    @Test
    void throwsWhenMeetingNotFound() {
        startServer(target -> "{\"code\":0,\"data\":{}}");
        var e = assertThrows(MeetingError.class,
            () -> read().get("m1", MeetingTestContext.withCredential()));
        assertEquals("MEETING_NOT_FOUND", e.code());
    }

    private MeetingReadService read() {
        return new MeetingReadService(new MeetingApiClient(json, server.baseUrl, Duration.ofSeconds(5)));
    }

    private void startServer(Function<String, String> responder) {
        server = FeishuServer.start(responder);
    }

    private static final class FeishuServer implements AutoCloseable {
        final HttpServer server;
        final String baseUrl;
        final AtomicReference<String> auth = new AtomicReference<>();

        private FeishuServer(HttpServer server) {
            this.server = server;
            this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        static FeishuServer start(Function<String, String> responder) {
            try {
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                var feishu = new FeishuServer(server);
                server.createContext("/", exchange -> {
                    String target = exchange.getRequestURI().getPath()
                        + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
                    feishu.auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    String body = responder.apply(target);
                    byte[] bytes = (body == null ? "{\"code\":0,\"data\":{}}" : body).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
                server.start();
                return feishu;
            } catch (IOException failed) {
                throw new IllegalStateException("无法启动飞书模拟服务", failed);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
