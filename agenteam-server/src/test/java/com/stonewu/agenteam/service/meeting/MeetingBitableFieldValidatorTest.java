package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 字段映射与回写校验只依赖白名单与字段元数据，不访问飞书或数据库。 */
class MeetingBitableFieldValidatorTest {
    private final MeetingBitableFieldValidator validator = new MeetingBitableFieldValidator();

    @Test
    void acceptsValidMappingCoveringUniqueKey() {
        assertDoesNotThrow(() -> validator.validateFieldMapping(MeetingBusinessType.MEETING,
            Map.of("meeting_key", "会议唯一键", "title", "标题")));
    }

    @Test
    void rejectsEmptyMapping() {
        assertMappingInvalid(Map.of());
    }

    @Test
    void rejectsUnknownBusinessCode() {
        assertMappingInvalid(Map.of("meeting_key", "k", "bogus_code", "x"));
    }

    @Test
    void rejectsManualFieldAsWritableTarget() {
        assertMappingInvalid(Map.of("meeting_key", "k", "confirm_status", "确认状态"));
    }

    @Test
    void rejectsTwoBusinessFieldsMappedToSameFeishuField() {
        assertMappingInvalid(Map.of("meeting_key", "k", "title", "k"));
    }

    @Test
    void rejectsMappingMissingUniqueKey() {
        var failure = assertThrows(ApiException.class,
            () -> validator.validateFieldMapping(MeetingBusinessType.MEETING, Map.of("title", "标题")));
        assertEquals("MEETING_UNIQUE_KEY_MISSING", failure.code());
    }

    @Test
    void normalizesWritebackAndRejectsUnknownResultKey() {
        assertEquals(Map.of("sync_status", "同步状态"), validator.normalizeWriteback(Map.of("sync_status", "同步状态")));
        assertNull(validator.normalizeWriteback(Map.of()));
        assertNull(validator.normalizeWriteback(null));
        var failure = assertThrows(ApiException.class, () -> validator.normalizeWriteback(Map.of("bogus", "x")));
        assertEquals("MEETING_FIELD_MAPPING_INVALID", failure.code());
    }

    @Test
    void rejectsWritebackDuplicateFeishuField() {
        var failure = assertThrows(ApiException.class, () -> validator.normalizeWriteback(
            Map.of("sync_status", "f", "sync_version", "f")));
        assertEquals("MEETING_FIELD_MAPPING_INVALID", failure.code());
    }

    @Test
    void validatesFeishuFieldsAndRejectsReadonlyMissingOrUnsupported() {
        List<MeetingFieldMeta> fields = List.of(
            new MeetingFieldMeta("fld1", "文本", "1", "Text", false),
            new MeetingFieldMeta("fld2", "公式", "1", "Formula", false),
            new MeetingFieldMeta("fld3", "金额", "1", "Currency", false),
            new MeetingFieldMeta("fld4", "位置", "1", "Location", false));
        assertDoesNotThrow(() -> validator.validateFeishuFields(Map.of("meeting_key", "文本"), null, fields));
        assertThrows(ApiException.class, () -> validator.validateFeishuFields(Map.of("meeting_key", "不存在"), null, fields));
        assertThrows(ApiException.class, () -> validator.validateFeishuFields(Map.of("meeting_key", "公式"), null, fields));
        assertThrows(ApiException.class, () -> validator.validateFeishuFields(Map.of("meeting_key", "金额"), null, fields));
        assertThrows(ApiException.class, () -> validator.validateFeishuFields(Map.of("meeting_key", "位置"), null, fields));
    }

    private void assertMappingInvalid(Map<String, String> mapping) {
        var failure = assertThrows(ApiException.class,
            () -> validator.validateFieldMapping(MeetingBusinessType.MEETING, mapping));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getStatusCode());
    }
}
