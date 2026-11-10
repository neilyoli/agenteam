package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSyncOutboxSqlMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 可信回调的确认写入只允许人工确认字段，映射为飞书字段后进入版本化投递箱。 */
class MeetingConfirmationCommandServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsNonConfirmAndNonManualFields() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            var binding = binding(access, enterprise, connection(access, enterprise, owner), owner);
            var service = service(access);

            assertThrows(ApiException.class,
                () -> service.publish(enterprise, binding.getId(), "task-1", "cmd-1", Map.of("title", "标题")));
            assertThrows(ApiException.class,
                () -> service.publish(enterprise, binding.getId(), "task-1", "cmd-2", Map.of("execution_status", "已完成")));
            // 只有意见、缺少确认状态
            assertThrows(ApiException.class,
                () -> service.publish(enterprise, binding.getId(), "task-1", "cmd-3", Map.of("confirm_comment", "意见")));
        }
    }

    @Test
    void mapsConfirmFieldsToFeishuAndEnqueuesOutbox() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            var binding = binding(access, enterprise, connection(access, enterprise, owner), owner);
            var service = service(access);

            var result = service.publish(enterprise, binding.getId(), "task-1", "cmd-1", Map.of(
                "confirm_status", "confirmed", "confirm_comment", "同意", "confirm_user", "ou_owner", "confirm_time", 1_700_000_000_000L));
            assertEquals(binding.getId(), result.bindingId());
            assertEquals("task-1", result.businessKey());
            assertEquals("cmd-1", result.commandId());

            var outboxRow = access.mapper(MeetingSyncOutboxSqlMapper.class).selectOne(
                new LambdaQueryWrapper<MeetingSyncOutboxRow>()
                    .eq(MeetingSyncOutboxRow::getBindingId, binding.getId())
                    .eq(MeetingSyncOutboxRow::getBusinessKey, "task-1"));
            assertNotNull(outboxRow);
            assertEquals("pending", outboxRow.getStatus());
            Map<String, Object> payload = new ObjectMapper().readValue(outboxRow.getPayloadJson(),
                new TypeReference<Map<String, Object>>() {
                });
            assertEquals("confirmed", payload.get("确认状态"));
            assertEquals("同意", payload.get("确认意见"));
            assertEquals("ou_owner", payload.get("确认人"));
            assertEquals(1_700_000_000_000L, ((Number) payload.get("确认时间")).longValue());
        }
    }

    private MeetingConfirmationCommandService service(MybatisTestDatabase access) {
        return new MeetingConfirmationCommandService(access.mapper(MeetingBindingMapper.class),
            new MeetingSyncOutbox(access.mapper(MeetingSyncOutboxSqlMapper.class)), new ObjectMapper(), CLOCK);
    }

    private MeetingBindingRow binding(MybatisTestDatabase database, String enterprise, String connection, String owner) {
        var row = new MeetingBindingRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setRevision(1L);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setIntegrationId(connection);
        row.setName("会议督办宽表绑定");
        row.setAppToken("bascn" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        row.setTableId("tbl" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        row.setBusinessType("task");
        row.setKeyField("任务唯一键");
        row.setFieldMappingJson("{\"task_key\":\"任务唯一键\",\"confirm_status\":\"确认状态\",\"confirm_comment\":\"确认意见\","
            + "\"confirm_user\":\"确认人\",\"confirm_time\":\"确认时间\"}");
        row.setStatus("enabled");
        row.setSchemaRevision("0".repeat(64));
        row.setOwnerUserId(owner);
        row.setConfigJson("{}");
        database.mapper(MeetingBindingMapper.class).insert(row);
        return row;
    }

    private String user(MybatisTestDatabase database) {
        var row = new com.stonewu.agenteam.model.user.entity.AppUserRow();
        row.setId(UUID.randomUUID().toString());
        row.setUsername("meeting_command_" + row.getId());
        row.setUsernameNormalized(row.getUsername());
        row.setPasswordHash("meeting-command-fixture");
        row.setDisplayName("会议确认命令测试用户");
        database.mapper(com.stonewu.agenteam.mapper.user.AppUserTableMapper.class).insert(row);
        return row.getId();
    }

    private String enterprise(MybatisTestDatabase database, String creator) {
        var row = new EnterpriseRow();
        row.setId(UUID.randomUUID().toString());
        row.setName("会议确认命令测试企业");
        row.setCreatedBy(creator);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setQuotaPeriodStart(Instant.now());
        row.setQuotaPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        database.mapper(EnterpriseTableMapper.class).insert(row);
        return row.getId();
    }

    private void member(MybatisTestDatabase database, String enterprise, String user) {
        var row = new EnterpriseMemberRow();
        row.setEnterpriseId(enterprise);
        row.setUserId(user);
        row.setDisplayName("会议确认命令测试成员");
        row.setJoinedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        database.mapper(IdentityQueryMapper.class).insert(row);
    }

    private String connection(MybatisTestDatabase database, String enterprise, String actor) {
        var row = new EnterpriseIntegrationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setProviderCode("feishu");
        row.setName("飞书会议确认命令测试接入");
        row.setExternalAppId("cli_" + UUID.randomUUID());
        row.setPublicLoginKey(UUID.randomUUID().toString());
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        database.mapper(EnterpriseIntegrationMapper.class).insert(row);
        return row.getId();
    }
}
