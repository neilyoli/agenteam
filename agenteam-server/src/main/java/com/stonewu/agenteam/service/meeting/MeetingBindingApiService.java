package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.request.MeetingBindingEnabledRequest;
import com.stonewu.agenteam.model.meeting.request.MeetingBindingWriteRequest;
import com.stonewu.agenteam.model.meeting.response.MeetingBindingView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 字段绑定由管理员配置；映射与回写在保存时按真实飞书 schema 校验。
 * 连接目标（Base/Table/业务类型）创建后不可变，字段映射可重校验更新。
 */
@Service
public class MeetingBindingApiService {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final AuthContextService identity;
    private final MeetingBindingMapper bindings;
    private final MeetingBindingStore store;
    private final IntegrationQueryService integrations;
    private final IntegrationTokenService tokens;
    private final MeetingBitableClient bitable;
    private final MeetingBitableFieldValidator validator;
    private final ResourceJson resourceJson;
    private final ObjectMapper json;
    private final Clock clock;

    public MeetingBindingApiService(AuthContextService identity, MeetingBindingMapper bindings,
                                    MeetingBindingStore store, IntegrationQueryService integrations,
                                    IntegrationTokenService tokens, MeetingBitableClient bitable,
                                    MeetingBitableFieldValidator validator, ResourceJson resourceJson,
                                    ObjectMapper json, Clock clock) {
        this.identity = identity;
        this.bindings = bindings;
        this.store = store;
        this.integrations = integrations;
        this.tokens = tokens;
        this.bitable = bitable;
        this.validator = validator;
        this.resourceJson = resourceJson;
        this.json = json;
        this.clock = clock;
    }

    public List<MeetingBindingView> list(String enterprise, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        return bindings.selectList(new LambdaQueryWrapper<MeetingBindingRow>()
            .eq(MeetingBindingRow::getEnterpriseId, enterprise).ne(MeetingBindingRow::getStatus, "deleted")
            .orderByAsc(MeetingBindingRow::getCreatedAt)).stream().map(this::view).toList();
    }

    public ApiOperationResult create(String enterprise, MeetingBindingWriteRequest input, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        MeetingBusinessType businessType = requireBusinessType(input.businessType());
        validator.validateFieldMapping(businessType, requireMapping(input.fieldMapping()));
        Map<String, String> writeback = validator.normalizeWriteback(input.writeback());

        var integration = requireFeishuIntegration(enterprise, input.integrationId());
        IntegrationApplication application = integrations.application(integration);
        var token = tokens.get(application);
        var fields = bitable.listFields(application.id(), token.value(), input.appToken(), input.tableId());
        validator.validateFeishuFields(requireMapping(input.fieldMapping()), writeback, fields);

        var now = clock.instant();
        var row = new MeetingBindingRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setRevision(1L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setIntegrationId(input.integrationId());
        row.setName(input.name());
        row.setAppToken(input.appToken());
        row.setTableId(input.tableId());
        row.setBusinessType(businessType.code());
        row.setKeyField(requireMapping(input.fieldMapping()).get(MeetingFieldCode.uniqueKeyFor(businessType).code()));
        row.setFieldMappingJson(writeJson(input.fieldMapping()));
        row.setWritebackJson(writeJson(writeback));
        row.setStatus(input.enabled() ? "enabled" : "disabled");
        row.setSchemaRevision(schemaRevision(fields));
        row.setOwnerUserId(actor.userId());
        row.setConfigJson("{}");
        store.insert(row);
        return ApiOperationResult.of(201, view(row));
    }

    public ApiOperationResult update(String enterprise, String id, MeetingBindingWriteRequest input,
                                     HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        var row = require(enterprise, id);
        MeetingBusinessType businessType = requireBusinessType(row.getBusinessType());
        Map<String, String> mapping = input.fieldMapping() == null ? readMapping(row.getFieldMappingJson())
            : requireMapping(input.fieldMapping());
        validator.validateFieldMapping(businessType, mapping);
        Map<String, String> writeback = input.writeback() == null ? readMap(row.getWritebackJson())
            : validator.normalizeWriteback(input.writeback());

        var integration = requireFeishuIntegration(enterprise, row.getIntegrationId());
        IntegrationApplication application = integrations.application(integration);
        var token = tokens.get(application);
        var fields = bitable.listFields(application.id(), token.value(), row.getAppToken(), row.getTableId());
        validator.validateFeishuFields(mapping, writeback, fields);

        row.setName(input.name() == null ? row.getName() : input.name());
        row.setKeyField(mapping.get(MeetingFieldCode.uniqueKeyFor(businessType).code()));
        row.setFieldMappingJson(writeJson(mapping));
        row.setWritebackJson(writeJson(writeback));
        row.setSchemaRevision(schemaRevision(fields));
        row.setUpdatedAt(clock.instant());
        store.save(row);
        return ApiOperationResult.of(200, view(row));
    }

    public ApiOperationResult enabled(String enterprise, String id, MeetingBindingEnabledRequest input,
                                      HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        var row = require(enterprise, id);
        row.setStatus(input.enabled() ? "enabled" : "disabled");
        row.setUpdatedAt(clock.instant());
        store.save(row);
        return ApiOperationResult.of(200, view(row));
    }

    public ApiOperationResult delete(String enterprise, String id, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        var row = require(enterprise, id);
        row.setStatus("deleted");
        row.setUpdatedAt(clock.instant());
        store.save(row);
        return ApiOperationResult.of(200, Map.of());
    }

    private void requireManage(AuthContext actor) {
        if (!actor.permissions().contains("agent.run")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "没有执行此操作的权限");
        }
    }

    private MeetingBusinessType requireBusinessType(String code) {
        MeetingBusinessType type = MeetingBusinessType.of(code);
        if (type == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_BUSINESS_TYPE_INVALID",
                "业务类型只支持 meeting 或 task。");
        }
        return type;
    }

    private Map<String, String> requireMapping(Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_FIELD_MAPPING_INVALID", "字段映射不能为空。");
        }
        return mapping;
    }

    private com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow requireFeishuIntegration(
        String enterprise, String integrationId) {
        var integration = integrations.require(enterprise, integrationId);
        if (!"feishu".equals(integration.getProviderCode()) || !"enabled".equals(integration.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_INTEGRATION_UNAVAILABLE", "所选飞书应用不可用或未启用。");
        }
        return integration;
    }

    private MeetingBindingRow require(String enterprise, String id) {
        var row = bindings.selectOne(new LambdaQueryWrapper<MeetingBindingRow>()
            .eq(MeetingBindingRow::getEnterpriseId, enterprise).eq(MeetingBindingRow::getId, id));
        if (row == null || "deleted".equals(row.getStatus())) {
            throw ResourceAuthorizationService.unavailable();
        }
        return row;
    }

    private String writeJson(Map<String, String> value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("字段映射无法保存", failure);
        }
    }

    private Map<String, String> readMap(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return json.readValue(value, STRING_MAP);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("已保存的字段映射无法读取", failure);
        }
    }

    private Map<String, String> readMapping(String value) {
        Map<String, String> mapping = readMap(value);
        return mapping == null ? Map.of() : mapping;
    }

    private String schemaRevision(List<MeetingFieldMeta> fields) {
        var schema = json.createArrayNode();
        fields.stream().sorted((a, b) -> a.fieldName().compareTo(b.fieldName())).forEach(field ->
            schema.addObject().put("name", field.fieldName()).put("uiType", field.uiType() == null ? "" : field.uiType()));
        return resourceJson.hash(schema);
    }

    private MeetingBindingView view(MeetingBindingRow row) {
        Map<String, String> mapping = readMapping(row.getFieldMappingJson());
        return new MeetingBindingView(row.getId(), row.getEnterpriseId(), row.getIntegrationId(), row.getName(),
            row.getAppToken(), row.getTableId(), row.getBusinessType(), row.getKeyField(), mapping,
            readMap(row.getWritebackJson()), row.getStatus(), row.getSchemaRevision(), row.getOwnerUserId(),
            row.getRevision(), row.getCreatedAt() == null ? null : row.getCreatedAt().toString(),
            row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
    }
}
