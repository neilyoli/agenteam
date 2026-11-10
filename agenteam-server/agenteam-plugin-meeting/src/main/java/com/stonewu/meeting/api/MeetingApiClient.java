package com.stonewu.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.plugin.api.JavaPluginContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * 飞书 Open API 客户端。使用 JDK {@link HttpClient} 与宿主下发的短期凭据头，不引入额外依赖。
 * 只读会议资料；目标资源（app_token / table_id）不在此类中出现，由宿主受控命令另行处理。
 * 端点与字段以飞书官方契约为准，真实租户 POC 前按已知契约实现并保留脱敏。
 */
public final class MeetingApiClient {
    private static final String AUTH_HEADER = "Authorization";
    private static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final Duration timeout;

    public MeetingApiClient(ObjectMapper json, String baseUrl, Duration timeout) {
        this.json = json;
        this.baseUrl = blankToDefault(baseUrl);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public static String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    private static String blankToDefault(String value) {
        return value == null || value.isBlank() ? DEFAULT_BASE_URL : value.strip();
    }

    /** 查询会议列表。query 为已编码的查询串（不含问号），返回 data.items 数组。 */
    public JsonNode searchMeetings(String query, JavaPluginContext context) {
        return getJson("/open-apis/vc/v1/meetings?" + query, context);
    }

    /** 读取单个会议，返回 data.meeting。 */
    public JsonNode getMeeting(String meetingId, JavaPluginContext context) {
        return getJson("/open-apis/vc/v1/meetings/" + encode(meetingId), context);
    }

    /** 读取参会人列表，返回 data.participants 数组。 */
    public JsonNode getParticipants(String meetingId, String query, JavaPluginContext context) {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        return getJson("/open-apis/vc/v1/meetings/" + encode(meetingId) + "/participant_list" + suffix, context);
    }

    /** 读取妙记元数据，返回 data（含 minute_token / 就绪信息）。 */
    public JsonNode getMinutesMeta(String minuteToken, JavaPluginContext context) {
        return getJson("/open-apis/minutes/v1/minutes/" + encode(minuteToken), context);
    }

    /** 分段读取妙记正文，返回 data（含 paragraphs）。 */
    public JsonNode getMinutes(String minuteToken, String query, JavaPluginContext context) {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        return getJson("/open-apis/minutes/v1/minutes/" + encode(minuteToken) + suffix, context);
    }

    /** 分段读取逐字稿，返回 data（含 utterances）。 */
    public JsonNode getTranscript(String minuteToken, String query, JavaPluginContext context) {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        return getJson("/open-apis/minutes/v1/minutes/" + encode(minuteToken) + "/transcript" + suffix, context);
    }

    private JsonNode getJson(String pathAndQuery, JavaPluginContext context) {
        context.cancellation().requireActive();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + pathAndQuery))
            .timeout(timeout)
            .header(AUTH_HEADER, bearer(context))
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            context.beforeSend();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return decode(response, pathAndQuery);
        } catch (HttpTimeoutException e) {
            throw new MeetingApiException(MeetingApiException.Kind.TIMEOUT, "飞书接口超时", e);
        } catch (IOException e) {
            throw new MeetingApiException(MeetingApiException.Kind.RETRYABLE, "飞书接口网络异常", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MeetingApiException(MeetingApiException.Kind.UNKNOWN, "调用被中断", e);
        }
    }

    private String bearer(JavaPluginContext context) {
        Map<String, String> headers = context.credentialHeaders();
        String value = headers == null ? null : headers.get(AUTH_HEADER);
        if (value == null || value.isBlank()) {
            throw new MeetingApiException(MeetingApiException.Kind.AUTH_EXPIRED, "缺少飞书访问凭据，请先完成授权");
        }
        return value;
    }

    private JsonNode decode(HttpResponse<String> response, String pathAndQuery) throws IOException {
        JsonNode root = json.readTree(response.body());
        if (root == null || root.isMissingNode()) {
            throw new MeetingApiException(MeetingApiException.Kind.UNKNOWN, "飞书返回空响应");
        }
        int code = root.path("code").asInt(-1);
        if (code == 0) {
            return root.path("data");
        }
        throw classify(code, root.path("msg").asText(""), response.statusCode());
    }

    private MeetingApiException classify(int code, String msg, int httpStatus) {
        if (httpStatus == 429 || code == 99991400) {
            return new MeetingApiException(MeetingApiException.Kind.RATE_LIMITED, msg);
        }
        if (httpStatus == 401 || code == 99991661 || code == 99991663) {
            return new MeetingApiException(MeetingApiException.Kind.AUTH_EXPIRED, msg);
        }
        if (httpStatus == 403 || code == 99991401 || code == 99991668) {
            return new MeetingApiException(MeetingApiException.Kind.PERMISSION_DENIED, msg);
        }
        if (httpStatus == 404) {
            return new MeetingApiException(MeetingApiException.Kind.NOT_FOUND, msg);
        }
        if (code == 99991402 || code == 99991403 || code == 10030) {
            return new MeetingApiException(MeetingApiException.Kind.INVALID_ARGUMENT, msg);
        }
        if (httpStatus >= 500 || code == 99991667) {
            return new MeetingApiException(MeetingApiException.Kind.RETRYABLE, msg);
        }
        return new MeetingApiException(MeetingApiException.Kind.UNKNOWN, msg);
    }

    private static String encode(String value) {
        return value == null ? "" : value;
    }
}
