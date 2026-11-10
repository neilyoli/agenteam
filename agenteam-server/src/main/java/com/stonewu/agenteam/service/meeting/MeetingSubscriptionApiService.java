package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSubscriptionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import com.stonewu.agenteam.model.meeting.request.MeetingSubscriptionEnabledRequest;
import com.stonewu.agenteam.model.meeting.request.MeetingSubscriptionWriteRequest;
import com.stonewu.agenteam.model.meeting.response.MeetingSubscriptionView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.plugin.JavaPluginRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 订阅由本人配置并固定插件版本；启停只影响后续事件，不回溯历史。
 */
@Service
public class MeetingSubscriptionApiService {
    private final AuthContextService identity;
    private final MeetingSubscriptionMapper subscriptions;
    private final EnterpriseIntegrationMapper integrations;
    private final EnterpriseMapper enterprises;
    private final JavaPluginRegistry plugins;
    private final MeetingEventPolicy policy;
    private final Clock clock;

    public MeetingSubscriptionApiService(AuthContextService identity, MeetingSubscriptionMapper subscriptions,
                                         EnterpriseIntegrationMapper integrations, EnterpriseMapper enterprises,
                                         JavaPluginRegistry plugins, MeetingEventPolicy policy, Clock clock) {
        this.identity = identity;
        this.subscriptions = subscriptions;
        this.integrations = integrations;
        this.enterprises = enterprises;
        this.plugins = plugins;
        this.policy = policy;
        this.clock = clock;
    }

    public List<MeetingSubscriptionView> list(String enterprise, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return subscriptions.selectList(new LambdaQueryWrapper<MeetingSubscriptionRow>()
            .eq(MeetingSubscriptionRow::getEnterpriseId, enterprise).ne(MeetingSubscriptionRow::getStatus, "deleted")
            .orderByAsc(MeetingSubscriptionRow::getCreatedAt)).stream().map(this::view).toList();
    }

    @Transactional
    public ApiOperationResult create(String enterprise, MeetingSubscriptionWriteRequest input, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        enterprises.lockEnterprise(enterprise).orElseThrow();
        validateIntegration(enterprise, input.integrationId());
        var installed = plugins.current(enterprise, "agenteam.meeting");
        if (installed == null || !"active".equals(installed.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_PLUGIN_UNAVAILABLE", "请先安装并启用会议插件，再配置事件订阅。");
        }
        policy.select(actor, input.hireId(), input.agentVersionId());
        var existing = subscriptions.selectOne(new LambdaQueryWrapper<MeetingSubscriptionRow>()
            .eq(MeetingSubscriptionRow::getEnterpriseId, enterprise)
            .eq(MeetingSubscriptionRow::getIntegrationId, input.integrationId())
            .eq(MeetingSubscriptionRow::getEventType, input.eventType()));
        var row = existing == null ? newRow(enterprise) : existing;
        row.setIntegrationId(input.integrationId());
        row.setEventType(input.eventType());
        row.setHireId(input.hireId());
        row.setAgentVersionId(input.agentVersionId());
        row.setPluginVersion(installed.getPluginVersion());
        row.setOwnerUserId(actor.userId());
        row.setStatus(input.enabled() ? "enabled" : "disabled");
        row.setUpdatedAt(clock.instant());
        if (existing == null) {
            subscriptions.insert(row);
        } else {
            subscriptions.updateById(row);
        }
        return ApiOperationResult.of(201, view(row));
    }

    @Transactional
    public ApiOperationResult enabled(String enterprise, String id, MeetingSubscriptionEnabledRequest input,
                                      HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var row = require(enterprise, id);
        row.setStatus(input.enabled() ? "enabled" : "disabled");
        row.setUpdatedAt(clock.instant());
        subscriptions.updateById(row);
        return ApiOperationResult.of(200, view(row));
    }

    @Transactional
    public ApiOperationResult delete(String enterprise, String id, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var row = require(enterprise, id);
        row.setStatus("deleted");
        row.setUpdatedAt(clock.instant());
        subscriptions.updateById(row);
        return ApiOperationResult.of(200, Map.of());
    }

    private void requireManage(AuthContext actor) {
        if (!actor.permissions().contains("agent.run")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "没有执行此操作的权限");
        }
    }

    private void validateIntegration(String enterprise, String integrationId) {
        var integration = integrations.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise)
            .eq(EnterpriseIntegrationRow::getId, integrationId));
        if (integration == null || !"feishu".equals(integration.getProviderCode())
            || !"enabled".equals(integration.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_INTEGRATION_UNAVAILABLE", "所选飞书应用不可用或未启用。");
        }
    }

    private MeetingSubscriptionRow require(String enterprise, String id) {
        var row = subscriptions.selectOne(new LambdaQueryWrapper<MeetingSubscriptionRow>()
            .eq(MeetingSubscriptionRow::getEnterpriseId, enterprise).eq(MeetingSubscriptionRow::getId, id));
        if (row == null || "deleted".equals(row.getStatus())) {
            throw ResourceAuthorizationService.unavailable();
        }
        return row;
    }

    private MeetingSubscriptionRow newRow(String enterprise) {
        var row = new MeetingSubscriptionRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setRevision(1L);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        row.setConfigJson("{}");
        return row;
    }

    private MeetingSubscriptionView view(MeetingSubscriptionRow row) {
        return new MeetingSubscriptionView(row.getId(), row.getEnterpriseId(), row.getIntegrationId(), row.getEventType(),
            row.getStatus(), row.getHireId(), row.getAgentVersionId(), row.getPluginVersion(), row.getOwnerUserId(),
            row.getRevision(), row.getCreatedAt() == null ? null : row.getCreatedAt().toString(),
            row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
    }
}
