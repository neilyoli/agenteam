package com.stonewu.agenteam.schema;

import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingConfirmationMapper;
import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingConfirmationRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实隔离数据库验证确认轮次建表、唯一键、检查约束，以及回答的版本、状态与事件去重栅栏。 */
class MeetingConfirmationSchemaTest {
    private static final Set<String> TABLES = Set.of("meeting_confirmation");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void confirmationTableEnforcesUniquenessChecksAndForeignKeys() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            assertTrue(access.mapper(SchemaSnapshotMapper.class).tables().containsAll(TABLES));

            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            String connection = connection(access, enterprise, owner);
            var binding = binding(access, enterprise, connection, owner, "task");
            var mapper = access.mapper(MeetingConfirmationMapper.class);

            mapper.insert(confirmation(enterprise, binding.getId(), "task-1"));
            assertThrows(DataIntegrityViolationException.class,
                () -> mapper.insert(confirmation(enterprise, binding.getId(), "task-1")));

            var badStatus = confirmation(enterprise, binding.getId(), "task-bad-status");
            badStatus.setStatus("bogus");
            assertThrows(DataAccessException.class, () -> mapper.insert(badStatus));

            var badRevision = confirmation(enterprise, binding.getId(), "task-bad-revision");
            badRevision.setRevision(0L);
            assertThrows(DataAccessException.class, () -> mapper.insert(badRevision));

            var missingBinding = confirmation(enterprise, "missing-binding", "task-no-binding");
            assertThrows(DataIntegrityViolationException.class, () -> mapper.insert(missingBinding));
        }
    }

    @Test
    void answerGuardsByStatusVersionAndEventDedup() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            var binding = binding(access, enterprise, connection(access, enterprise, owner), owner, "task");
            var mapper = access.mapper(MeetingConfirmationMapper.class);

            mapper.insert(confirmation(enterprise, binding.getId(), "task-1"));
            assertEquals(1, mapper.answer(enterprise, binding.getId(), "task-1", 1L, "confirmed", "同意", "evt-1", now()));
            var answered = mapper.find(enterprise, binding.getId(), "task-1");
            assertEquals("confirmed", answered.getStatus());
            assertEquals("同意", answered.getConfirmComment());
            assertEquals("evt-1", answered.getLastEventId());
            assertEquals(2L, answered.getRevision());

            // 状态已推进：重复回答不再生效
            assertEquals(0, mapper.answer(enterprise, binding.getId(), "task-1", 1L, "confirmed", "x", "evt-2", now()));

            // 已处理事件但状态仍为待确认的边界行：事件去重栅栏拒绝同一事件
            mapper.insert(confirmation(enterprise, binding.getId(), "task-dup", "evt-1"));
            assertEquals(0, mapper.answer(enterprise, binding.getId(), "task-dup", 1L, "confirmed", "x", "evt-1", now()));
            assertEquals("pending", mapper.find(enterprise, binding.getId(), "task-dup").getStatus());

            // 新一轮重置到版本 2 后，旧版本回调被拒绝
            mapper.beginRound(enterprise, binding.getId(), "task-1", 2L, "owner-2", "ou_owner_2", "pending", now());
            assertEquals(0, mapper.answer(enterprise, binding.getId(), "task-1", 1L, "confirmed", "x", "evt-3", now()));
            assertEquals(1, mapper.answer(enterprise, binding.getId(), "task-1", 2L, "confirmed", "好", "evt-3", now()));
        }
    }

    @Test
    void beginRoundResetsAndRecordCardBackfills() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            var binding = binding(access, enterprise, connection(access, enterprise, owner), owner, "task");
            var mapper = access.mapper(MeetingConfirmationMapper.class);

            mapper.insert(confirmation(enterprise, binding.getId(), "task-1", "evt-1"));
            mapper.recordCard(enterprise, binding.getId(), "task-1", "om-msg-1", "cmd-1", now());
            var recorded = mapper.find(enterprise, binding.getId(), "task-1");
            assertEquals("om-msg-1", recorded.getCardMessageId());
            assertEquals("cmd-1", recorded.getCardCommandId());

            mapper.beginRound(enterprise, binding.getId(), "task-1", 3L, "owner-3", "ou_owner_3", "pending", now());
            var reset = mapper.find(enterprise, binding.getId(), "task-1");
            assertEquals(3L, reset.getTaskVersion());
            assertEquals("owner-3", reset.getOwnerUserId());
            assertEquals("ou_owner_3", reset.getOwnerSubject());
            assertEquals("pending", reset.getStatus());
            assertNull(reset.getConfirmComment());
            assertNull(reset.getCardMessageId());
            assertNull(reset.getLastEventId());
        }
    }

    private MeetingConfirmationRow confirmation(String enterprise, String bindingId, String businessKey) {
        return confirmation(enterprise, bindingId, businessKey, null);
    }

    private MeetingConfirmationRow confirmation(String enterprise, String bindingId, String businessKey, String lastEventId) {
        var row = new MeetingConfirmationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setRevision(1L);
        row.setCreatedAt(NOW);
        row.setUpdatedAt(NOW);
        row.setBindingId(bindingId);
        row.setBusinessKey(businessKey);
        row.setTaskVersion(1L);
        row.setOwnerUserId("owner-1");
        row.setOwnerSubject("ou_owner_1");
        row.setStatus("pending");
        row.setLastEventId(lastEventId);
        row.setConfigJson("{}");
        return row;
    }

    private Timestamp now() {
        return Timestamp.from(NOW);
    }

    private MeetingBindingRow binding(MybatisTestDatabase database, String enterprise, String connection,
                                      String owner, String businessType) {
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
        row.setBusinessType(businessType);
        row.setKeyField("任务ID");
        row.setFieldMappingJson("{\"task_key\":\"任务ID\"}");
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
        row.setUsername("meeting_confirm_" + row.getId());
        row.setUsernameNormalized(row.getUsername());
        row.setPasswordHash("meeting-confirm-fixture");
        row.setDisplayName("会议确认结构测试用户");
        database.mapper(com.stonewu.agenteam.mapper.user.AppUserTableMapper.class).insert(row);
        return row.getId();
    }

    private String enterprise(MybatisTestDatabase database, String creator) {
        var row = new EnterpriseRow();
        row.setId(UUID.randomUUID().toString());
        row.setName("会议确认结构测试企业");
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
        row.setDisplayName("会议确认测试成员");
        row.setJoinedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        database.mapper(IdentityQueryMapper.class).insert(row);
    }

    private String connection(MybatisTestDatabase database, String enterprise, String actor) {
        var row = new EnterpriseIntegrationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setProviderCode("feishu");
        row.setName("飞书会议确认测试接入");
        row.setExternalAppId("cli_" + UUID.randomUUID());
        row.setPublicLoginKey(UUID.randomUUID().toString());
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        database.mapper(EnterpriseIntegrationMapper.class).insert(row);
        return row.getId();
    }
}
