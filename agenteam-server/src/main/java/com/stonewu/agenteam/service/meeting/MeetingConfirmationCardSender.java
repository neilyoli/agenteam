package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 向负责人 open_id 投递确认卡片。沿用宿主令牌缓存与脱敏网络层，飞书 receive_id_type=open_id，
 * 与群聊卡片路径（chat_id）分离；失败不输出凭证或响应正文。
 * 网络层未取得明确结果时抛 UNKNOWN（不能自动重发，防止重复打扰负责人），
 * 飞书明确拒绝时抛 REJECTED（限流可重试，其余阻断待修复）。
 */
@Component
public class MeetingConfirmationCardSender {
    private static final String RATE_LIMIT_CODE = "99991400";

    private final IntegrationHttpClient http;
    private final IntegrationEndpoints endpoints;
    private final IntegrationQueryService integrations;
    private final IntegrationTokenService tokens;
    private final ObjectMapper json;

    public MeetingConfirmationCardSender(IntegrationHttpClient http, IntegrationEndpoints endpoints,
                                         IntegrationQueryService integrations, IntegrationTokenService tokens,
                                         ObjectMapper json) {
        this.http = http;
        this.endpoints = endpoints;
        this.integrations = integrations;
        this.tokens = tokens;
        this.json = json;
    }

    /** 返回飞书消息编号；结果不明或被拒绝时抛投递异常，由卡片 Worker 决定重试或人工核查。 */
    public String send(String enterpriseId, String integrationId, String ownerSubject,
                       JsonNode card, String commandId) {
        var integration = integrations.require(enterpriseId, integrationId);
        var application = integrations.application(integration);
        var token = tokens.get(application);
        ObjectNode body = json.createObjectNode()
            .put("msg_type", "interactive")
            .put("content", card.toString())
            .put("receive_id", ownerSubject)
            .put("uuid", commandId);
        ChannelHttpResponse response;
        try {
            response = http.post(application.id(), endpoints.feishu("/open-apis/im/v1/messages"),
                Map.of("receive_id_type", "open_id"), body, token.value());
        } catch (ChannelProviderException failure) {
            // 请求未取得明确结果：消息可能已经发出，uuid 去重窗口不可靠，不能盲目重发。
            throw new MeetingCardDeliveryException(MeetingCardDeliveryException.Outcome.UNKNOWN, false,
                "确认卡片投递未取得明确结果，已停止自动重发，请人工核查负责人是否收到。", failure);
        }
        if (response.status() < 200 || response.status() >= 300 || response.body() == null
            || !"0".equals(response.body().path("code").asText())) {
            String code = response.body() == null ? "" : response.body().path("code").asText("");
            boolean retryable = response.status() == 429 || RATE_LIMIT_CODE.equals(code);
            throw new MeetingCardDeliveryException(MeetingCardDeliveryException.Outcome.REJECTED, retryable,
                "确认卡片投递被飞书拒绝（" + code + "）。", null);
        }
        String messageId = response.body().path("data").path("message_id").asText(null);
        if (messageId == null || messageId.isBlank()) {
            throw new MeetingCardDeliveryException(MeetingCardDeliveryException.Outcome.UNKNOWN, false,
                "飞书未返回卡片消息编号，投递结果不明，请人工核查。", null);
        }
        return messageId;
    }
}
