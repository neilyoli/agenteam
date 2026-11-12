package com.stonewu.meeting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.plugin.api.JavaPluginContext;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** 飞书 Open API 客户端。凭据只使用宿主本次调用注入的短期请求头。 */
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

    /** 按会议号获取会议实例列表。 */
    public JsonNode searchMeetings(String query, JavaPluginContext context) {
        return getJson(meetingSearchPath(query), context);
    }

    /** 获取会议详情，并由飞书在同一响应中返回参会人列表。 */
    public JsonNode getMeeting(String meetingId, JavaPluginContext context) {
        return getJson(meetingDetailPath(meetingId), context);
    }

    static String meetingSearchPath(String query) {
        return "/open-apis/vc/v1/meetings/list_by_no?" + query;
    }

    static String meetingDetailPath(String meetingId) {
        return "/open-apis/vc/v1/meetings/" + encode(meetingId)
            + "?with_participants=true&with_meeting_ability=false&user_id_type=open_id";
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
            return decode(response);
        } catch (HttpTimeoutException failure) {
            throw new MeetingApiException(MeetingApiException.Kind.TIMEOUT, "飞书接口超时", failure);
        } catch (IOException failure) {
            throw new MeetingApiException(MeetingApiException.Kind.RETRYABLE, "飞书接口网络异常", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new MeetingApiException(MeetingApiException.Kind.UNKNOWN, "调用被中断", failure);
        }
    }

    private String bearer(JavaPluginContext context) {
        Map<String, String> headers = context.credentialHeaders();
        String value = headers == null ? null : headers.get(AUTH_HEADER);
        if (value == null || value.isBlank()) {
            throw new MeetingApiException(MeetingApiException.Kind.AUTH_EXPIRED, "缺少飞书访问凭据，请检查企业飞书接入绑定");
        }
        return value;
    }

    private JsonNode decode(HttpResponse<String> response) throws IOException {
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

    private MeetingApiException classify(int code, String message, int httpStatus) {
        if (httpStatus == 429 || code == 99991400) {
            return new MeetingApiException(MeetingApiException.Kind.RATE_LIMITED, message);
        }
        if (httpStatus == 401 || code == 99991661 || code == 99991663) {
            return new MeetingApiException(MeetingApiException.Kind.AUTH_EXPIRED, message);
        }
        if (httpStatus == 403 || code == 99991401 || code == 99991668 || code == 121005) {
            return new MeetingApiException(MeetingApiException.Kind.PERMISSION_DENIED, message);
        }
        if (httpStatus == 404 || code == 121004) {
            return new MeetingApiException(MeetingApiException.Kind.NOT_FOUND, message);
        }
        if (code == 99991402 || code == 99991403 || code == 10030 || code == 121003) {
            return new MeetingApiException(MeetingApiException.Kind.INVALID_ARGUMENT, message);
        }
        if (httpStatus >= 500 || code == 99991667 || code == 121001) {
            return new MeetingApiException(MeetingApiException.Kind.RETRYABLE, message);
        }
        return new MeetingApiException(MeetingApiException.Kind.UNKNOWN, message);
    }

    /** 对路径段和查询参数进行 UTF-8 百分号编码。 */
    static String encode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
