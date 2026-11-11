package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingTaskIdentityMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingTaskSourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskIdentityRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskSourceRow;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会议任务的受控只读检索。身份与来源来自 MySQL 技术索引；任务业务状态（任务状态等）
 * 只有在显式请求时从飞书任务宽表实时读取，本服务不缓存、不落库，避免形成第二套任务状态。
 */
@Service
public class MeetingTaskQueryService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final List<String> LIVE_CODES = List.of("task_status", "title", "owner", "due_date");

    private final MeetingTaskIdentityMapper identities;
    private final MeetingTaskSourceMapper sources;
    private final MeetingBindingMapper bindings;
    private final MeetingBitableClient bitable;
    private final IntegrationQueryService integrations;
    private final IntegrationTokenService tokens;
    private final ResourceJson json;

    public MeetingTaskQueryService(MeetingTaskIdentityMapper identities, MeetingTaskSourceMapper sources,
                                   MeetingBindingMapper bindings, MeetingBitableClient bitable,
                                   IntegrationQueryService integrations, IntegrationTokenService tokens,
                                   ResourceJson json) {
        this.identities = identities;
        this.sources = sources;
        this.bindings = bindings;
        this.bitable = bitable;
        this.integrations = integrations;
        this.tokens = tokens;
        this.json = json;
    }

    /** 检索历史任务：按任务编号、标题关键词、会议编号或负责人过滤。 */
    public JsonNode search(String enterpriseId, String taskKey, String query, String meetingId,
                           String ownerOpenId, boolean includeLiveStatus, int limit) {
        int bounded = bounded(limit);
        List<MeetingTaskIdentityRow> rows;
        if (taskKey != null && !taskKey.isBlank()) {
            var identity = identities.find(enterpriseId, taskKey);
            rows = identity == null ? List.of() : List.of(identity);
        } else {
            List<String> meetingScoped = null;
            if (meetingId != null && !meetingId.isBlank()) {
                meetingScoped = sources.selectList(new LambdaQueryWrapper<MeetingTaskSourceRow>()
                        .eq(MeetingTaskSourceRow::getEnterpriseId, enterpriseId)
                        .eq(MeetingTaskSourceRow::getMeetingId, meetingId))
                    .stream().map(MeetingTaskSourceRow::getTaskKey).distinct().toList();
                if (meetingScoped.isEmpty()) {
                    return empty("该会议尚未沉淀任何任务。");
                }
            }
            var wrapper = new LambdaQueryWrapper<MeetingTaskIdentityRow>()
                .eq(MeetingTaskIdentityRow::getEnterpriseId, enterpriseId)
                .eq(MeetingTaskIdentityRow::getStatus, "active")
                .like(query != null && !query.isBlank(), MeetingTaskIdentityRow::getTitleNormalized,
                    normalizeQuery(query))
                .eq(ownerOpenId != null && !ownerOpenId.isBlank(), MeetingTaskIdentityRow::getOwnerOpenId, ownerOpenId)
                .in(meetingScoped != null, MeetingTaskIdentityRow::getId, meetingScoped)
                .orderByDesc(MeetingTaskIdentityRow::getUpdatedAt)
                .last("LIMIT " + bounded);
            rows = identities.selectList(wrapper);
        }
        return render(enterpriseId, rows, includeLiveStatus);
    }

    /** 检索上游任务候选：除当前会议外（通常更早会议）提及的任务。 */
    public JsonNode upstreamSearch(String enterpriseId, String meetingId, String query, String ownerOpenId,
                                   int limit) {
        int bounded = bounded(limit);
        var wrapper = new LambdaQueryWrapper<MeetingTaskIdentityRow>()
            .eq(MeetingTaskIdentityRow::getEnterpriseId, enterpriseId)
            .eq(MeetingTaskIdentityRow::getStatus, "active")
            .like(query != null && !query.isBlank(), MeetingTaskIdentityRow::getTitleNormalized,
                normalizeQuery(query))
            .eq(ownerOpenId != null && !ownerOpenId.isBlank(), MeetingTaskIdentityRow::getOwnerOpenId, ownerOpenId)
            .orderByDesc(MeetingTaskIdentityRow::getUpdatedAt)
            .last("LIMIT " + bounded * 3);
        List<MeetingTaskIdentityRow> candidates = identities.selectList(wrapper);
        if (candidates.isEmpty()) {
            return empty("没有检索到候选上游任务。");
        }
        // 只保留确有当前会议之外来源的任务；没有检索到时如实返回空，不推断为明确未覆盖。
        List<MeetingTaskIdentityRow> upstream = new ArrayList<>();
        for (var candidate : candidates) {
            if (upstream.size() >= bounded) {
                break;
            }
            boolean outside = sources.listByTask(enterpriseId, candidate.getId()).stream()
                .anyMatch(source -> meetingId == null || meetingId.isBlank()
                    || !meetingId.equals(source.getMeetingId()));
            if (outside) {
                upstream.add(candidate);
            }
        }
        return render(enterpriseId, upstream, false);
    }

    private JsonNode render(String enterpriseId, List<MeetingTaskIdentityRow> rows, boolean includeLiveStatus) {
        if (rows.isEmpty()) {
            return empty("没有检索到匹配的会议任务。");
        }
        MeetingBindingRow binding = null;
        if (includeLiveStatus) {
            binding = bindings.selectOne(new LambdaQueryWrapper<MeetingBindingRow>()
                .eq(MeetingBindingRow::getEnterpriseId, enterpriseId)
                .eq(MeetingBindingRow::getBusinessType, MeetingBusinessType.TASK.code()));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskKey", row.getId());
            item.put("title", row.getTitleCurrent());
            item.put("ownerOpenId", row.getOwnerOpenId());
            item.put("ownerName", row.getOwnerName());
            item.put("sourceCount", row.getSourceCount());
            List<Map<String, Object>> sourceItems = new ArrayList<>();
            for (var source : sources.listByTask(enterpriseId, row.getId())) {
                Map<String, Object> sourceItem = new LinkedHashMap<>();
                sourceItem.put("meetingId", source.getMeetingId());
                sourceItem.put("meetingTitle", source.getMeetingTitle());
                sourceItem.put("runId", source.getRunId());
                sourceItems.add(sourceItem);
            }
            item.put("sources", sourceItems);
            if (includeLiveStatus) {
                item.put("live", liveStatus(enterpriseId, binding, row.getId()));
            }
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("content", "检索到 " + items.size() + " 条任务。");
        response.put("isError", false);
        return json.tree(response);
    }

    /** 按任务编号读取飞书最新状态；读取失败时标记 unavailable，不缓存、不影响其余结果。 */
    private Map<String, Object> liveStatus(String enterpriseId, MeetingBindingRow binding, String taskKey) {
        Map<String, Object> live = new LinkedHashMap<>();
        if (binding == null || !"enabled".equals(binding.getStatus())) {
            live.put("unavailable", true);
            live.put("reason", "任务绑定未启用，无法读取飞书最新状态。");
            return live;
        }
        try {
            var integration = integrations.require(enterpriseId, binding.getIntegrationId());
            String token = tokens.get(integrations.application(integration)).value();
            JsonNode fields = bitable.findRecordFields(binding.getIntegrationId(), token, binding.getAppToken(),
                binding.getTableId(), binding.getKeyField(), taskKey);
            if (fields == null) {
                live.put("unavailable", true);
                live.put("reason", "飞书任务宽表尚未写入该任务或写入尚未完成。");
                return live;
            }
            Map<String, String> mapping = readMapping(binding.getFieldMappingJson());
            for (String code : LIVE_CODES) {
                String feishuField = mapping.get(code);
                if (feishuField != null && fields.has(feishuField)) {
                    live.put(code, json.object(fields.path(feishuField)));
                }
            }
            live.put("recordId", fields.path("__record_id__").asText(null));
        } catch (MeetingBitableException | ChannelProviderException failure) {
            live.put("unavailable", true);
            live.put("reason", "飞书最新状态暂时不可读，可稍后重试或改为读取任务索引。");
        }
        return live;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readMapping(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return (Map<String, String>) (Map<?, ?>) json.object(json.read(value));
        } catch (RuntimeException failure) {
            throw new com.stonewu.agenteam.service.http.ApiException(HttpStatus.CONFLICT, "MEETING_BINDING_INVALID",
                "字段映射无法读取，请重新校验绑定。", failure);
        }
    }

    private JsonNode empty(String content) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", List.of());
        response.put("content", content);
        response.put("isError", false);
        return json.tree(response);
    }

    private static int bounded(int limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? DEFAULT_LIMIT : limit));
    }

    /** 关键词规范化并与 LIKE 通配转义：小写、折叠空白，转义 % _ 与反斜杠；空关键词返回 null。 */
    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalized = query.strip().toLowerCase().replaceAll("\\s+", " ");
        return normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
