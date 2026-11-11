package com.stonewu.agenteam.service.integration.feishu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import com.stonewu.agenteam.service.plugin.JavaPluginCredentialProvider;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.tool.api.ToolContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 会议插件凭据桥接：通过当前 Run 用户的飞书绑定定位企业应用，再复用宿主 Token 缓存。
 *
 * <p>飞书用户绑定只用于确定执行用户和所属接入，不把登录会话误当作 API Token。
 * 企业应用 Token 由 IntegrationTokenService 获取、缓存和刷新，插件 Worker 只接收本次调用所需的短期请求头。</p>
 */
@Component
public class FeishuMeetingCredentialProvider implements JavaPluginCredentialProvider {
    public static final String PLUGIN_ID = "agenteam.meeting";

    private final UserChannelBindingMapper bindings;
    private final EnterpriseIntegrationMapper connections;
    private final IntegrationQueryService integrations;
    private final IntegrationTokenService tokens;

    public FeishuMeetingCredentialProvider(UserChannelBindingMapper bindings,
                                           EnterpriseIntegrationMapper connections,
                                           IntegrationQueryService integrations,
                                           IntegrationTokenService tokens) {
        this.bindings = bindings;
        this.connections = connections;
        this.integrations = integrations;
        this.tokens = tokens;
    }

    @Override
    public String pluginId() {
        return PLUGIN_ID;
    }

    @Override
    public Map<String, String> resolve(ToolContext context, String credentialId) {
        if (credentialId != null && !credentialId.isBlank()) {
            throw new ChannelProviderException("FEISHU_PLUGIN_CREDENTIAL_OVERRIDE_UNSUPPORTED",
                "会议插件使用企业飞书接入自动获取访问凭据，请移除手动连接凭据配置。");
        }
        String enterpriseId = context.enterpriseId();
        String actorId = context.actorId();
        if (enterpriseId == null || enterpriseId.isBlank() || actorId == null || actorId.isBlank()) {
            throw bindingRequired();
        }

        List<UserChannelBindingRow> userBindings = bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterpriseId)
            .eq(UserChannelBindingRow::getUserId, actorId)
            .eq(UserChannelBindingRow::getStatus, "active"));
        if (userBindings.isEmpty()) {
            throw bindingRequired();
        }

        List<String> connectionIds = userBindings.stream().map(UserChannelBindingRow::getConnectionId).distinct().toList();
        List<EnterpriseIntegrationRow> available = connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterpriseId)
            .in(EnterpriseIntegrationRow::getId, connectionIds)
            .eq(EnterpriseIntegrationRow::getProviderCode, "feishu")
            .eq(EnterpriseIntegrationRow::getPurpose, "enterprise")
            .eq(EnterpriseIntegrationRow::getStatus, "enabled")
            .isNotNull(EnterpriseIntegrationRow::getIdentityVerifiedAt)
            .isNotNull(EnterpriseIntegrationRow::getExternalTenantId));
        Map<String, EnterpriseIntegrationRow> availableById = available.stream()
            .collect(java.util.stream.Collectors.toMap(EnterpriseIntegrationRow::getId, value -> value));

        List<UserChannelBindingRow> eligibleBindings = userBindings.stream()
            .filter(binding -> "feishu_open_id".equals(binding.getExternalSubjectType()))
            .filter(binding -> binding.getExternalSubjectId() != null && !binding.getExternalSubjectId().isBlank())
            .filter(binding -> availableById.containsKey(binding.getConnectionId()))
            .toList();
        if (eligibleBindings.isEmpty()) {
            throw new ChannelProviderException("FEISHU_INTEGRATION_UNAVAILABLE",
                "当前飞书账号绑定的企业应用未启用或尚未完成企业身份校验，请联系企业管理员检查飞书接入。");
        }
        if (eligibleBindings.size() > 1) {
            throw new ChannelProviderException("FEISHU_BINDING_AMBIGUOUS",
                "当前用户存在多个可用的企业飞书应用绑定，无法安全地自动选择，请联系企业管理员指定默认接入。");
        }

        EnterpriseIntegrationRow connection = availableById.get(eligibleBindings.getFirst().getConnectionId());
        ChannelAccessToken token = tokens.get(integrations.application(connection));
        return Map.of("Authorization", "Bearer " + token.value());
    }

    private static ChannelProviderException bindingRequired() {
        return new ChannelProviderException("FEISHU_USER_BINDING_REQUIRED",
            "当前 AgenTeam 执行用户尚未绑定可用的企业飞书账号，请先在企业账号绑定中完成飞书绑定后重试。");
    }
}
