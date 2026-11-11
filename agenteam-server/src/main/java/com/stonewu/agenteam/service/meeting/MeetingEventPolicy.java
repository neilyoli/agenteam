package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSubscriptionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import com.stonewu.agenteam.service.execution.ExecutionConfigurationService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.plugin.JavaPluginRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 订阅匹配与执行选择：事件类型、插件版本与雇佣固定，不复制旧固定会议工作流。
 */
@Service
public class MeetingEventPolicy {
    private final MeetingSubscriptionMapper subscriptions;
    private final JavaPluginRegistry plugins;
    private final AgentHireMapper hires;
    private final ExecutionConfigurationService configurations;

    public MeetingEventPolicy(MeetingSubscriptionMapper subscriptions, JavaPluginRegistry plugins,
                              AgentHireMapper hires, ExecutionConfigurationService configurations) {
        this.subscriptions = subscriptions;
        this.plugins = plugins;
        this.hires = hires;
        this.configurations = configurations;
    }

    public MeetingSubscriptionRow findEnabled(String enterprise, String integrationId, String eventType) {
        return subscriptions.selectOne(new LambdaQueryWrapper<MeetingSubscriptionRow>()
            .eq(MeetingSubscriptionRow::getEnterpriseId, enterprise)
            .eq(MeetingSubscriptionRow::getIntegrationId, integrationId)
            .eq(MeetingSubscriptionRow::getEventType, eventType).eq(MeetingSubscriptionRow::getStatus, "enabled"));
    }

    /** 插件升级或卸载后，旧订阅不再触发，直到管理员重新确认版本。 */
    public boolean pluginActive(String enterprise, String expectedVersion) {
        var installed = plugins.current(enterprise, "agenteam.meeting");
        return installed != null && "active".equals(installed.getStatus())
            && expectedVersion.equals(installed.getPluginVersion());
    }

    public ExecutionConfigurationService.Selection select(AuthContext actor, String hireId, String version) {
        var hire = hires.find(actor.enterpriseId(), actor.userId(), hireId, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        if (!"active".equals(hire.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_UNAVAILABLE", "请先恢复该员工的雇佣，再启用会议事件订阅。");
        }
        var selected = configurations.normal(actor, hire.agentId(), version);
        if (!hire.id().equals(selected.hireId())) {
            throw ResourceAuthorizationService.unavailable();
        }
        return selected;
    }
}
