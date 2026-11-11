package com.stonewu.agenteam.service.skill;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.mapper.plugin.BuiltinPluginResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 平台内置技能具有真实发布版本，可直接绑定到智能体；插件安装后随企业初始化自动导入、发布并授权使用。
 */
@Service
public class BuiltinSkillResourceService {
    private static final Logger LOG = LoggerFactory.getLogger(BuiltinSkillResourceService.class);
    private final EnterpriseMapper enterprises;
    private final BuiltinPluginResourceMapper selection;
    private final BuiltinSkillRegistry registry;
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final ResourceAuthorizationMapper grants;
    private final ResourceJson json;
    private final AuditEventMapper audit;
    private final Clock clock;

    public BuiltinSkillResourceService(EnterpriseMapper enterprises, BuiltinPluginResourceMapper selection,
                                       BuiltinSkillRegistry registry,
                                       ResourceMapper resources, ResourceVersionMapper versions,
                                       ResourceAuthorizationMapper grants, ResourceJson json, AuditEventMapper audit,
                                       Clock clock) {
        this.enterprises = enterprises;
        this.selection = selection;
        this.registry = registry;
        this.resources = resources;
        this.versions = versions;
        this.grants = grants;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void initialize(String enterprise) {
        if (enterprises.lockEnterprise(enterprise).isEmpty()) {
            return;
        }
        var administrator = selection.administrator(enterprise);
        if (administrator.isEmpty()) {
            LOG.warn("企业 {} 暂无有效管理员，内置技能将在下次初始化时补充", enterprise);
            return;
        }
        for (var adapter : registry.list()) {
            initialize(enterprise, administrator.get(), adapter);
        }
    }

    private void initialize(String enterprise, String owner, BuiltinSkillAdapter adapter) {
        String resourceId = stableId("builtin-skill:" + enterprise + ":" + adapter.code());
        var pluginVersionIds = resolvePluginVersions(enterprise, adapter.pluginCodes());
        var config = configuration(adapter, pluginVersionIds);
        String descriptor = json.hash(json.tree(
            Map.of("name", adapter.name(), "description", adapter.description(), "config", config)));
        String versionId = stableId(resourceId + ":" + descriptor);
        var resource = resources.find(enterprise, resourceId, true, true).orElse(null);
        if (resource != null) {
            if (!resource.source().equals("builtin")) {
                throw new IllegalStateException("内置技能的固定编号与已有资源冲突");
            }
            // 已有版本、管理员停用状态和调整过的授权均保留，不因重启恢复。
            if (resource.deletedAt() != null || versions.find(enterprise, versionId).isPresent()) {
                return;
            }
            resources.draft(resource, adapter.name(), adapter.description(), null, config, owner, clock.instant());
        } else {
            resources.create(resourceId, enterprise, ResourceKind.SKILL, adapter.name(), adapter.description(), null,
                owner, "builtin", config, clock.instant());
            grants.replaceGrants(enterprise, resourceId, owner,
                Set.of(new ResourceGrantSpec("enterprise", enterprise, "view"),
                    new ResourceGrantSpec("enterprise", enterprise, "use")), clock.instant());
        }
        resource = resources.find(enterprise, resourceId, false, true).orElseThrow();
        versions.publish(versionId, resource, "更新平台内置技能", owner, dependencies(pluginVersionIds), clock.instant());
        resources.publish(resource, versionId, clock.instant());
        audit.append(enterprise, "system:builtin-skills", "系统", "resource.builtin.initialize", "resource",
            resourceId, "初始化平台内置技能版本",
            json.write(json.tree(
                Map.of("builtinCode", adapter.code(), "versionId", versionId, "versionNo", resource.nextVersionNo()))),
            UUID.randomUUID().toString(), clock.instant());
    }

    /** 把技能声明的内置插件代码解析为已发布版本编号，缺失时跳过依赖，避免阻断技能发布。 */
    private List<String> resolvePluginVersions(String enterprise, List<String> pluginCodes) {
        var result = new ArrayList<String>();
        for (String code : pluginCodes) {
            String id = stableId("builtin-plugin:" + enterprise + ":" + code);
            var plugin = resources.find(enterprise, id, false, false).orElse(null);
            if (plugin != null && plugin.publishedVersionId() != null) {
                result.add(plugin.publishedVersionId());
            }
        }
        return result;
    }

    private List<DependencyBinding> dependencies(List<String> pluginVersionIds) {
        var result = new ArrayList<DependencyBinding>();
        int ordinal = 0;
        for (String versionId : pluginVersionIds) {
            result.add(new DependencyBinding(versionId, "plugin", "pluginVersionIds", ordinal++));
        }
        return result;
    }

    private ObjectNode configuration(BuiltinSkillAdapter adapter, List<String> pluginVersionIds) {
        return json.tree(Map.of("icon", adapter.icon(), "color", adapter.color(), "scenario", adapter.scenario(),
                "inputDescription", adapter.inputDescription(), "instructions", adapter.instructions(),
                "outputDescription", adapter.outputDescription(), "example", adapter.example(),
                "pluginVersionIds", pluginVersionIds, "knowledgeVersionIds", List.of(), "showInWorkspace",
                adapter.showInWorkspace()))
            .deepCopy();
    }

    private String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
