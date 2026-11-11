package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 飞书多维表格 v1 的窄接口，沿用宿主渠道 HTTP 客户端与令牌缓存。
 * 只暴露绑定校验与投递所需的 listFields / findRecordId / create / update 四个操作，
 * 异常按可重试与阻断分类，不输出凭证与原始响应正文。
 */
@Component
public class MeetingBitableClient {
    private static final Set<String> RATE_LIMIT_CODES = Set.of("99991400", "230020");
    private static final String BITABLE = "/open-apis/bitable/v1/apps/";

    private final IntegrationHttpClient http;
    private final IntegrationEndpoints endpoints;
    private final ObjectMapper json;

    public MeetingBitableClient(IntegrationHttpClient http, IntegrationEndpoints endpoints, ObjectMapper json) {
        this.http = http;
        this.endpoints = endpoints;
        this.json = json;
    }

    public List<MeetingFieldMeta> listFields(String connection, String token, String appToken, String tableId) {
        List<MeetingFieldMeta> fields = new ArrayList<>();
        String pageToken = null;
        try {
            do {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("page_size", "100");
                if (pageToken != null) {
                    query.put("page_token", pageToken);
                }
                JsonNode body = requireSuccess(http.get(connection,
                    endpoints.feishu(BITABLE + appToken + "/tables/" + tableId + "/fields"), query, token));
                JsonNode data = body.path("data");
                for (JsonNode item : data.path("items")) {
                    if (item.path("field_name").isTextual() && !item.path("field_name").asText().isBlank()) {
                        fields.add(new MeetingFieldMeta(item.path("field_id").asText(null),
                            item.path("field_name").asText(), item.path("type").asText(null),
                            item.path("ui_type").asText(null), item.path("is_primary").asBoolean(false)));
                    }
                }
                if (!data.path("has_more").asBoolean(false)) {
                    break;
                }
                pageToken = data.path("page_token").asText(null);
            } while (pageToken != null && !pageToken.isBlank());
        } catch (ChannelProviderException failure) {
            throw new MeetingBitableException("飞书字段列表请求失败", true, failure);
        }
        return fields;
    }

    /** 分页读取飞书记录，供督办扫描读取任务宽表最新状态；不返回凭证或原始响应正文。 */
    public MeetingRecordPage listRecords(String connection, String token, String appToken, String tableId,
                                         String pageToken, int pageSize) {
        try {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("page_size", String.valueOf(Math.max(1, Math.min(500, pageSize))));
            if (pageToken != null && !pageToken.isBlank()) {
                query.put("page_token", pageToken);
            }
            JsonNode body = requireSuccess(http.get(connection,
                endpoints.feishu(BITABLE + appToken + "/tables/" + tableId + "/records"), query, token));
            JsonNode data = body.path("data");
            List<MeetingRecord> records = new ArrayList<>();
            for (JsonNode item : data.path("items")) {
                String recordId = item.path("record_id").asText(null);
                JsonNode fields = item.path("fields");
                if (recordId != null && fields != null && fields.isObject()) {
                    records.add(new MeetingRecord(recordId, fields));
                }
            }
            return new MeetingRecordPage(records, data.path("has_more").asBoolean(false),
                data.path("page_token").asText(null));
        } catch (ChannelProviderException failure) {
            throw new MeetingBitableException("飞书记录列表请求失败", true, failure);
        }
    }

    public String findRecordId(String connection, String token, String appToken, String tableId,
                               String keyField, String keyValue) {
        try {
            ObjectNode condition = json.createObjectNode().put("field_name", keyField).put("operator", "is");
            condition.putArray("value").add(keyValue);
            ObjectNode filter = json.createObjectNode().put("conjunction", "and");
            filter.putArray("conditions").add(condition);
            ObjectNode body = json.createObjectNode();
            body.putArray("field_names").add(keyField);
            body.set("filter", filter);
            body.put("page_size", 2);
            JsonNode data = requireSuccess(http.post(connection,
                endpoints.feishu(BITABLE + appToken + "/tables/" + tableId + "/records/search"), Map.of(), body, token))
                .path("data");
            if (data.path("has_more").asBoolean(false)
                || data.path("total").isIntegralNumber() && data.path("total").asLong() > 1) {
                throw new MeetingBitableException("目标表存在重复唯一键，拒绝选择任意记录更新", null, null, false);
            }
            JsonNode items = data.path("items");
            if (!items.isArray() || items.size() == 0) {
                return null;
            }
            if (items.size() > 1) {
                throw new MeetingBitableException("目标表存在重复唯一键，拒绝选择任意记录更新", null, null, false);
            }
            return items.get(0).path("record_id").asText();
        } catch (MeetingBitableException failure) {
            throw failure;
        } catch (ChannelProviderException failure) {
            throw new MeetingBitableException("飞书记录查询请求失败", true, failure);
        }
    }

    /** 按唯一键等值查询单条记录并返回全部字段；不存在返回 null，重复唯一键拒绝猜测。 */
    public JsonNode findRecordFields(String connection, String token, String appToken, String tableId,
                                     String keyField, String keyValue) {
        try {
            ObjectNode condition = json.createObjectNode().put("field_name", keyField).put("operator", "is");
            condition.putArray("value").add(keyValue);
            ObjectNode filter = json.createObjectNode().put("conjunction", "and");
            filter.putArray("conditions").add(condition);
            ObjectNode body = json.createObjectNode();
            body.set("filter", filter);
            body.put("page_size", 2);
            JsonNode data = requireSuccess(http.post(connection,
                endpoints.feishu(BITABLE + appToken + "/tables/" + tableId + "/records/search"), Map.of(), body, token))
                .path("data");
            if (data.path("has_more").asBoolean(false)
                || data.path("total").isIntegralNumber() && data.path("total").asLong() > 1) {
                throw new MeetingBitableException("目标表存在重复唯一键，拒绝选择任意记录更新", null, null, false);
            }
            JsonNode items = data.path("items");
            if (!items.isArray() || items.size() == 0) {
                return null;
            }
            if (items.size() > 1) {
                throw new MeetingBitableException("目标表存在重复唯一键，拒绝选择任意记录更新", null, null, false);
            }
            var record = items.get(0);
            String recordId = record.path("record_id").asText(null);
            var fields = record.path("fields");
            if (recordId == null || fields == null || !fields.isObject()) {
                return null;
            }
            var result = (ObjectNode) fields.deepCopy();
            result.put("__record_id__", recordId);
            return result;
        } catch (MeetingBitableException failure) {
            throw failure;
        } catch (ChannelProviderException failure) {
            throw new MeetingBitableException("飞书记录查询请求失败", true, failure);
        }
    }

    public String createRecord(String connection, String token, String appToken, String tableId,
                               String clientToken, Map<String, Object> fields) {
        try {
            ObjectNode body = json.createObjectNode();
            putFields(body.putObject("fields"), fields);
            body.put("client_token", clientToken);
            JsonNode record = requireSuccess(http.post(connection,
                endpoints.feishu(BITABLE + appToken + "/tables/" + tableId + "/records"), Map.of(), body, token))
                .path("data").path("record");
            String recordId = record.path("record_id").asText(null);
            if (recordId == null || recordId.isBlank()) {
                throw new MeetingBitableException("飞书创建响应缺少记录编号", null, null, false);
            }
            return recordId;
        } catch (MeetingBitableException failure) {
            throw failure;
        } catch (ChannelProviderException failure) {
            throw new MeetingBitableException("飞书记录创建请求失败", true, failure);
        }
    }

    public void updateRecord(String connection, String token, String appToken, String tableId,
                             String recordId, Map<String, Object> fields) {
        try {
            ObjectNode body = json.createObjectNode();
            putFields(body.putObject("fields"), fields);
            JsonNode record = requireSuccess(http.put(connection,
                endpoints.feishu(BITABLE + appToken + "/tables/" + tableId + "/records/" + recordId), body, token))
                .path("data").path("record");
            String updated = record.path("record_id").asText(null);
            if (updated == null || !recordId.equals(updated)) {
                throw new MeetingBitableException("飞书更新响应记录不匹配", null, null, false);
            }
        } catch (MeetingBitableException failure) {
            throw failure;
        } catch (ChannelProviderException failure) {
            throw new MeetingBitableException("飞书记录更新请求失败", true, failure);
        }
    }

    private JsonNode requireSuccess(ChannelHttpResponse response) {
        if (response.status() >= 500) {
            throw new MeetingBitableException("飞书服务暂时不可用", null, response.traceId(), true);
        }
        if (response.status() == 429) {
            throw new MeetingBitableException("飞书要求降低请求频率", null, response.traceId(), true);
        }
        JsonNode body = response.body();
        if (body == null || !body.isObject() || response.status() < 200 || response.status() >= 300) {
            throw new MeetingBitableException("飞书未接受本次请求", null, response.traceId(), true);
        }
        String code = body.path("code").asText();
        if (!"0".equals(code) || body.hasNonNull("error")) {
            throw new MeetingBitableException("飞书多维表格调用失败", code, response.traceId(),
                RATE_LIMIT_CODES.contains(code));
        }
        return body;
    }

    private void putFields(ObjectNode target, Map<String, Object> fields) {
        fields.forEach((name, value) -> {
            if (value != null) {
                target.set(name, json.valueToTree(value));
            }
        });
    }

    /** 一条飞书记录：record_id 与字段对象（字段名 → 值）。 */
    public record MeetingRecord(String recordId, JsonNode fields) {
    }

    /** 飞书记录分页结果。 */
    public record MeetingRecordPage(List<MeetingRecord> records, boolean hasMore, String pageToken) {
    }
}
