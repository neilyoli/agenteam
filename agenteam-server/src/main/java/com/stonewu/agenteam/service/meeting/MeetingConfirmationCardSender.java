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
 */
@Component
public class MeetingConfirmationCardSender {
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

    /** 返回飞书消息编号；投递未成功时抛渠道异常，由调用方决定重试与记录。 */
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
            throw new ChannelProviderException("MEETING_CARD_SEND_FAILED", "确认卡片投递未取得明确结果。", failure);
        }
        if (response.status() < 200 || response.status() >= 300 || response.body() == null
            || !"0".equals(response.body().path("code").asText())) {
            throw new ChannelProviderException("MEETING_CARD_SEND_FAILED", "确认卡片投递未成功。", null);
        }
        String messageId = response.body().path("data").path("message_id").asText(null);
        if (messageId == null || messageId.isBlank()) {
            throw new ChannelProviderException("MEETING_CARD_SEND_FAILED", "飞书未返回卡片消息编号。", null);
        }
        return messageId;
    }
}
