package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字段映射与回写配置的服务端校验。映射目标只能取业务字段白名单且必须覆盖业务唯一键；
 * 人工字段不可作为可写目标；飞书字段必须真实存在且类型可写，只读字段明确报错。
 */
@Component
public class MeetingBitableFieldValidator {

    /** 可写飞书字段交互类型；公式与货币只读不可写。 */
    private static final Set<String> WRITABLE_UI_TYPES = Set.of(
        "Text", "Number", "DateTime", "SingleSelect", "MultiSelect", "Url", "Attachment");

    /** 只读字段交互类型，写入必被飞书拒绝，保存配置时即拦截。 */
    private static final Set<String> READONLY_UI_TYPES = Set.of("Formula", "Currency");

    public MeetingBitableFieldValidator() {
    }

    /**
     * 校验字段映射：目标编码在白名单内、飞书字段名非空且不重复、不含人工字段、覆盖业务唯一键。
     */
    public void validateFieldMapping(MeetingBusinessType type, Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            throw invalidMapping("字段映射不能为空。");
        }
        Set<String> feishuFields = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String businessCode = entry.getKey();
            String feishuField = entry.getValue();
            if (businessCode == null || businessCode.isBlank() || feishuField == null || feishuField.isBlank()) {
                throw invalidMapping("字段映射的目标编码与飞书字段名均不能为空。");
            }
            MeetingFieldCode target = MeetingFieldCode.of(businessCode);
            if (target == null) {
                throw invalidMapping("业务字段编码不在白名单内：" + businessCode + "。");
            }
            if (target.writePolicy() == MeetingFieldWritePolicy.MANUAL) {
                throw invalidMapping("人工字段以飞书为唯一事实来源，不能配置为系统可写目标：" + businessCode + "。");
            }
            if (!targets.add(businessCode)) {
                throw invalidMapping("字段映射存在重复的业务字段编码：" + businessCode + "。");
            }
            if (!feishuFields.add(feishuField.trim())) {
                throw invalidMapping("多个业务字段映射到同一飞书字段：" + feishuField + "。");
            }
        }
        MeetingFieldCode uniqueKey = MeetingFieldCode.uniqueKeyFor(type);
        if (!targets.contains(uniqueKey.code())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_UNIQUE_KEY_MISSING",
                "字段映射必须覆盖业务唯一键：" + uniqueKey.code() + "。");
        }
    }

    /**
     * 归一化回写配置：结果键必须为结果键枚举、值为非空飞书字段名且不重复；空配置归一为 null。
     */
    public Map<String, String> normalizeWriteback(Map<String, String> writeback) {
        if (writeback == null || writeback.isEmpty()) {
            return null;
        }
        Set<String> feishuFields = new HashSet<>();
        Map<String, String> normalized = new HashMap<>();
        for (Map.Entry<String, String> entry : writeback.entrySet()) {
            String key = entry.getKey();
            String feishuField = entry.getValue();
            if (MeetingWritebackResult.of(key) == null) {
                throw invalidMapping("回写结果键不在支持范围内：" + key + "。");
            }
            if (feishuField == null || feishuField.isBlank() || !feishuFields.add(feishuField.trim())) {
                throw invalidMapping("回写配置的飞书字段名不能为空或重复：" + feishuField + "。");
            }
            normalized.put(key, feishuField.trim());
        }
        return normalized;
    }

    /**
     * 联网校验：映射与回写引用的飞书字段都存在且可写；只读或不支持的类型明确报错。
     */
    public void validateFeishuFields(Map<String, String> mapping, Map<String, String> writeback,
                                     List<MeetingFieldMeta> fields) {
        Map<String, String> uiTypeByName = new HashMap<>();
        for (MeetingFieldMeta field : fields) {
            uiTypeByName.put(field.fieldName(), field.uiType() == null ? "" : field.uiType());
        }
        validateReferenced(mapping, uiTypeByName);
        validateReferenced(writeback, uiTypeByName);
    }

    private void validateReferenced(Map<String, String> feishuFields, Map<String, String> uiTypeByName) {
        if (feishuFields == null) {
            return;
        }
        for (String feishuField : feishuFields.values()) {
            String uiType = uiTypeByName.get(feishuField);
            if (uiType == null) {
                throw invalidMapping("飞书字段不存在：" + feishuField + "。");
            }
            if (READONLY_UI_TYPES.contains(uiType)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_FIELD_UNSUPPORTED",
                    "飞书字段为只读类型，不可写入：" + feishuField + "（" + uiType + "）。");
            }
            if (!WRITABLE_UI_TYPES.contains(uiType)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_FIELD_UNSUPPORTED",
                    "飞书字段类型不支持写入：" + feishuField + "（" + uiType + "）。");
            }
        }
    }

    private static ApiException invalidMapping(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_FIELD_MAPPING_INVALID", message);
    }
}
