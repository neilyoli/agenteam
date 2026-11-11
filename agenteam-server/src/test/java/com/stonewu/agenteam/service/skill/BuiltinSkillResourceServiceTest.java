package com.stonewu.agenteam.service.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 内置技能随企业初始化自动导入、发布并绑定到声明插件，重复初始化幂等。 */
class BuiltinSkillResourceServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final EnterpriseMapper enterprises = mock(EnterpriseMapper.class);
    private final BuiltinPluginResourceMapper selection = mock(BuiltinPluginResourceMapper.class);
    private final ResourceMapper resources = mock(ResourceMapper.class);
    private final ResourceVersionMapper versions = mock(ResourceVersionMapper.class);
    private final ResourceAuthorizationMapper grants = mock(ResourceAuthorizationMapper.class);
    private final AuditEventMapper audit = mock(AuditEventMapper.class);
    private final ResourceJson json = new ResourceJson(new ObjectMapper());

    private final BuiltinSkillAdapter adapter = new BuiltinSkillAdapter("test-skill", "测试技能", "描述", "Lightbulb",
        "teal", "适用场景", "输入说明", "执行指令", "输出说明", "示例", true, List.of("meeting_management"));

    private final BuiltinSkillResourceService service =
        new BuiltinSkillResourceService(enterprises, selection, new BuiltinSkillRegistry(List.of(adapter)), resources,
            versions, grants, json, audit, CLOCK);

    @Test
    void importsPublishesAndBindsSkillOnFirstInitialization() {
        when(enterprises.lockEnterprise("ent")).thenReturn(Optional.of("ent"));
        when(selection.administrator("ent")).thenReturn(Optional.of("admin-1"));
        when(resources.find(eq("ent"), anyString(), eq(true), eq(true))).thenReturn(Optional.empty());
        when(resources.find(eq("ent"), anyString(), eq(false), eq(false)))
            .thenReturn(Optional.of(resource("plugin-1", "builtin", "plugin-v1")));
        when(resources.find(eq("ent"), anyString(), eq(false), eq(true)))
            .thenReturn(Optional.of(resource("skill-1", "builtin", "version-1")));
        when(versions.find(eq("ent"), anyString())).thenReturn(Optional.empty());

        service.initialize("ent");

        ArgumentCaptor<JsonNode> config = ArgumentCaptor.forClass(JsonNode.class);
        verify(resources).create(anyString(), eq("ent"), eq(ResourceKind.SKILL), eq("测试技能"), eq("描述"), isNull(),
            eq("admin-1"), eq("builtin"), config.capture(), eq(NOW));
        assertEquals("执行指令", config.getValue().path("instructions").asText());
        assertEquals("适用场景", config.getValue().path("scenario").asText());
        assertTrue(config.getValue().path("showInWorkspace").asBoolean());

        ArgumentCaptor<Set<ResourceGrantSpec>> granted = ArgumentCaptor.forClass(Set.class);
        verify(grants).replaceGrants(eq("ent"), anyString(), eq("admin-1"), granted.capture(), eq(NOW));
        assertEquals(Set.of(new ResourceGrantSpec("enterprise", "ent", "view"),
            new ResourceGrantSpec("enterprise", "ent", "use")), granted.getValue());

        ArgumentCaptor<List<DependencyBinding>> dependencies = ArgumentCaptor.forClass(List.class);
        verify(versions).publish(anyString(), any(), eq("更新平台内置技能"), eq("admin-1"), dependencies.capture(), eq(NOW));
        assertTrue(dependencies.getValue().contains(new DependencyBinding("plugin-v1", "plugin", "pluginVersionIds", 0)));
        verify(resources).publish(any(ResourceRecord.class), anyString(), eq(NOW));
    }

    @Test
    void repeatedInitializationKeepsExistingVersion() {
        when(enterprises.lockEnterprise("ent")).thenReturn(Optional.of("ent"));
        when(selection.administrator("ent")).thenReturn(Optional.of("admin-1"));
        when(resources.find(eq("ent"), anyString(), eq(true), eq(true)))
            .thenReturn(Optional.of(resource("skill-1", "builtin", "version-1")));
        when(resources.find(eq("ent"), anyString(), eq(false), eq(false))).thenReturn(Optional.empty());
        when(versions.find(eq("ent"), anyString())).thenReturn(Optional.of(mock(
            com.stonewu.agenteam.model.resource.entity.ResourceVersionRecord.class)));

        service.initialize("ent");

        verify(resources, never()).create(anyString(), anyString(), any(), anyString(), anyString(), any(), anyString(),
            anyString(), any(), any());
        verify(resources, never()).publish(any(ResourceRecord.class), anyString(), any());
        verify(versions, never()).publish(anyString(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void registryRejectsDuplicateSkillCode() {
        var duplicate = new BuiltinSkillAdapter("test-skill", "重复", "描述", "Feather", "amber", "场景", "输入", "指令",
            "输出", "示例", false, List.of());
        assertThrows(IllegalStateException.class, () -> new BuiltinSkillRegistry(List.of(adapter, duplicate)));
    }

    private ResourceRecord resource(String id, String source, String publishedVersionId) {
        return new ResourceRecord(id, "ent", ResourceKind.SKILL, "测试技能", "描述", null, "admin-1", "管理员", source,
            "published", publishedVersionId, 2, 1L, NOW, NOW, null, json.tree(Map.of()), "hash", null);
    }
}
