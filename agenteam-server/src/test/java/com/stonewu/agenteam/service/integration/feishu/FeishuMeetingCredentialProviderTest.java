package com.stonewu.agenteam.service.integration.feishu;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import com.stonewu.tool.api.ToolContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FeishuMeetingCredentialProviderTest {

    @Test
    void resolvesCachedTenantTokenFromCurrentUsersActiveFeishuBinding() {
        UserChannelBindingMapper bindings = mock(UserChannelBindingMapper.class);
        EnterpriseIntegrationMapper connections = mock(EnterpriseIntegrationMapper.class);
        IntegrationQueryService integrations = mock(IntegrationQueryService.class);
        IntegrationTokenService tokens = mock(IntegrationTokenService.class);
        FeishuMeetingCredentialProvider provider = new FeishuMeetingCredentialProvider(bindings, connections, integrations, tokens);

        ToolContext context = context("enterprise-1", "user-1");
        UserChannelBindingRow binding = binding("connection-1", "enterprise-1", "user-1");
        EnterpriseIntegrationRow connection = connection("connection-1", "enterprise-1");
        IntegrationApplication application = new IntegrationApplication("connection-1", "enterprise-1", "feishu",
            "tenant-1", "cli_test", 3L);
        when(bindings.selectList(any(Wrapper.class))).thenReturn(List.of(binding));
        when(connections.selectList(any(Wrapper.class))).thenReturn(List.of(connection));
        when(integrations.application(connection)).thenReturn(application);
        when(tokens.get(application)).thenReturn(new ChannelAccessToken("short-lived-token", 7200));

        assertEquals(Map.of("Authorization", "Bearer short-lived-token"), provider.resolve(context, null));
        verify(tokens).get(application);
        verifyNoMoreInteractions(tokens);
    }

    @Test
    void rejectsMissingRunActorBeforeReadingBindings() {
        UserChannelBindingMapper bindings = mock(UserChannelBindingMapper.class);
        FeishuMeetingCredentialProvider provider = new FeishuMeetingCredentialProvider(bindings,
            mock(EnterpriseIntegrationMapper.class), mock(IntegrationQueryService.class), mock(IntegrationTokenService.class));

        ChannelProviderException failure = assertThrows(ChannelProviderException.class,
            () -> provider.resolve(context("enterprise-1", null), null));

        assertEquals("FEISHU_USER_BINDING_REQUIRED", failure.code());
        verifyNoInteractions(bindings);
    }

    @Test
    void rejectsWhenCurrentUserHasNoActiveFeishuBinding() {
        UserChannelBindingMapper bindings = mock(UserChannelBindingMapper.class);
        when(bindings.selectList(any(Wrapper.class))).thenReturn(List.of());
        FeishuMeetingCredentialProvider provider = new FeishuMeetingCredentialProvider(bindings,
            mock(EnterpriseIntegrationMapper.class), mock(IntegrationQueryService.class), mock(IntegrationTokenService.class));

        ChannelProviderException failure = assertThrows(ChannelProviderException.class,
            () -> provider.resolve(context("enterprise-1", "user-1"), null));

        assertEquals("FEISHU_USER_BINDING_REQUIRED", failure.code());
    }

    @Test
    void rejectsAmbiguousEnterpriseFeishuBindingsInsteadOfChoosingOneSilently() {
        UserChannelBindingMapper bindings = mock(UserChannelBindingMapper.class);
        EnterpriseIntegrationMapper connections = mock(EnterpriseIntegrationMapper.class);
        UserChannelBindingRow first = binding("connection-1", "enterprise-1", "user-1");
        UserChannelBindingRow second = binding("connection-2", "enterprise-1", "user-1");
        when(bindings.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(connections.selectList(any(Wrapper.class))).thenReturn(List.of(
            connection("connection-1", "enterprise-1"), connection("connection-2", "enterprise-1")));
        FeishuMeetingCredentialProvider provider = new FeishuMeetingCredentialProvider(bindings, connections,
            mock(IntegrationQueryService.class), mock(IntegrationTokenService.class));

        ChannelProviderException failure = assertThrows(ChannelProviderException.class,
            () -> provider.resolve(context("enterprise-1", "user-1"), null));

        assertEquals("FEISHU_BINDING_AMBIGUOUS", failure.code());
    }

    @Test
    void rejectsManuallySuppliedCredentialSoPluginCannotBypassEnterpriseBinding() {
        FeishuMeetingCredentialProvider provider = new FeishuMeetingCredentialProvider(
            mock(UserChannelBindingMapper.class), mock(EnterpriseIntegrationMapper.class),
            mock(IntegrationQueryService.class), mock(IntegrationTokenService.class));

        ChannelProviderException failure = assertThrows(ChannelProviderException.class,
            () -> provider.resolve(context("enterprise-1", "user-1"), "manual-credential"));

        assertEquals("FEISHU_PLUGIN_CREDENTIAL_OVERRIDE_UNSUPPORTED", failure.code());
    }

    private static ToolContext context(String enterprise, String actor) {
        ToolContext context = mock(ToolContext.class);
        when(context.enterpriseId()).thenReturn(enterprise);
        when(context.actorId()).thenReturn(actor);
        return context;
    }

    private static UserChannelBindingRow binding(String connection, String enterprise, String user) {
        UserChannelBindingRow row = new UserChannelBindingRow();
        row.setId("binding-" + connection);
        row.setConnectionId(connection);
        row.setEnterpriseId(enterprise);
        row.setUserId(user);
        row.setExternalSubjectType("feishu_open_id");
        row.setExternalSubjectId("ou_" + user);
        row.setExternalUnionId("on_" + user);
        row.setStatus("active");
        row.setAuthorizedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return row;
    }

    private static EnterpriseIntegrationRow connection(String id, String enterprise) {
        EnterpriseIntegrationRow row = new EnterpriseIntegrationRow();
        row.setId(id);
        row.setEnterpriseId(enterprise);
        row.setProviderCode("feishu");
        row.setPurpose("enterprise");
        row.setExternalTenantId("tenant-1");
        row.setExternalAppId("cli_test");
        row.setStatus("enabled");
        row.setIdentityVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        row.setCredentialRevision(3L);
        return row;
    }
}
