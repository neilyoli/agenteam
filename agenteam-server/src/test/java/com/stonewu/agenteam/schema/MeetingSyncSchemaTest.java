package com.stonewu.agenteam.schema;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSyncOutboxSqlMapper;
import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import com.stonewu.agenteam.service.meeting.MeetingSyncOutbox;
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

/** 在真实隔离数据库验证绑定与投递箱的建表、唯一键、外键、检查约束，以及版本化入队与领取栅栏。 */
class MeetingSyncSchemaTest {
    private static final Set<String> TABLES = Set.of("meeting_binding", "meeting_sync_outbox");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void bindingAndOutboxEnforceUniquenessAndChecks() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            assertTrue(access.mapper(SchemaSnapshotMapper.class).tables().containsAll(TABLES));
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            String connection = connection(access, enterprise, owner);

            var binding = binding(access, enterprise, connection, owner, "meeting");
            assertThrows(DataIntegrityViolationException.class,
                () -> access.mapper(MeetingBindingMapper.class)
                    .insert(binding(access, enterprise, connection, owner, "meeting")));

            var invalidStatus = binding(access, enterprise, connection, owner, "task");
            invalidStatus.setStatus("bogus");
            assertThrows(DataAccessException.class, () -> access.mapper(MeetingBindingMapper.class).insert(invalidStatus));

            access.mapper(MeetingSyncOutboxSqlMapper.class).insert(outbox(enterprise, binding.getId(), "meeting-1"));
            assertThrows(DataIntegrityViolationException.class,
                () -> access.mapper(MeetingSyncOutboxSqlMapper.class)
                    .insert(outbox(enterprise, binding.getId(), "meeting-1")));

            var badStatus = outbox(enterprise, binding.getId(), "meeting-2");
            badStatus.setStatus("claimed");
            assertThrows(DataAccessException.class,
                () -> access.mapper(MeetingSyncOutboxSqlMapper.class).insert(badStatus));

            var badVersion = outbox(enterprise, binding.getId(), "meeting-3");
            badVersion.setDesiredVersion(1L);
            badVersion.setDeliveredVersion(2L);
            assertThrows(DataAccessException.class,
                () -> access.mapper(MeetingSyncOutboxSqlMapper.class).insert(badVersion));
        }
    }

    @Test
    void upsertBumpsDesiredVersionAndKeepsStableCreateToken() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            var sync = sync(access);
            var binding = binding(access, "meeting");

            sync.enqueue(outbox(binding.getEnterpriseId(), binding.getId(), "meeting-1", "cmd-1", "token-A"), NOW);
            var afterFirst = requireOutbox(access, binding.getId(), "meeting-1");
            assertEquals(1L, afterFirst.getDesiredVersion());
            assertEquals("token-A", afterFirst.getCreateToken());
            assertEquals("pending", afterFirst.getStatus());

            sync.enqueue(outbox(binding.getEnterpriseId(), binding.getId(), "meeting-1", "cmd-2", "token-B"), NOW);
            var afterSecond = requireOutbox(access, binding.getId(), "meeting-1");
            assertEquals(2L, afterSecond.getDesiredVersion());
            assertEquals("token-A", afterSecond.getCreateToken());
            assertEquals("cmd-2", afterSecond.getCommandId());
            assertEquals(0, afterSecond.getAttemptCount());
            assertEquals(1, access.mapper(MeetingSyncOutboxSqlMapper.class).selectCount(null));
        }
    }

    @Test
    void claimFencesAndCompleteAdvancesDeliveredVersion() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            var sync = sync(access);
            var binding = binding(access, "meeting");
            sync.enqueue(outbox(binding.getEnterpriseId(), binding.getId(), "meeting-1", "cmd-1", "token-A"), NOW);

            var claimed = sync.claim(binding.getId(), "meeting-1", NOW);
            assertTrue(claimed.isPresent());
            assertTrue(sync.claim(binding.getId(), "meeting-1", NOW).isEmpty());
            assertEquals(1, requireOutbox(access, binding.getId(), "meeting-1").getAttemptCount());

            sync.complete(claimed.get(), NOW);
            var done = requireOutbox(access, binding.getId(), "meeting-1");
            assertEquals(1L, done.getDeliveredVersion());
            assertEquals("delivered", done.getStatus());
            assertNull(done.getClaimToken());
            assertEquals(0, done.getAttemptCount());
        }
    }

    @Test
    void failBacksOffBlockAndRetryReset() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            var sync = sync(access);
            var binding = binding(access, "meeting");

            sync.enqueue(outbox(binding.getEnterpriseId(), binding.getId(), "meeting-1", "cmd-1", "token-A"), NOW);
            var claimed = sync.claim(binding.getId(), "meeting-1", NOW).orElseThrow();
            sync.fail(claimed, "SYNC_FAILED", null, NOW);
            var failed = requireOutbox(access, binding.getId(), "meeting-1");
            assertEquals("pending", failed.getStatus());
            assertEquals("SYNC_FAILED", failed.getLastErrorCode());
            assertTrue(failed.getNextAttemptAt().isAfter(NOW));
            assertNull(failed.getClaimToken());

            sync.enqueue(outbox(binding.getEnterpriseId(), binding.getId(), "meeting-2", "cmd-2", "token-B"), NOW);
            var second = sync.claim(binding.getId(), "meeting-2", NOW).orElseThrow();
            sync.block(second, "MEETING_FIELD_UNSUPPORTED", null, NOW);
            assertEquals("blocked", requireOutbox(access, binding.getId(), "meeting-2").getStatus());

            sync.retry(binding.getId(), "meeting-2", NOW);
            var reset = requireOutbox(access, binding.getId(), "meeting-2");
            assertEquals("pending", reset.getStatus());
            assertEquals(0, reset.getAttemptCount());
            assertNull(reset.getLastErrorCode());
        }
    }

    private MeetingSyncOutbox sync(MybatisTestDatabase access) {
        return new MeetingSyncOutbox(access.mapper(MeetingSyncOutboxSqlMapper.class));
    }

    private MeetingBindingRow binding(MybatisTestDatabase access, String businessType) {
        String owner = user(access);
        String enterprise = enterprise(access, owner);
        member(access, enterprise, owner);
        String connection = connection(access, enterprise, owner);
        return binding(access, enterprise, connection, owner, businessType);
    }

    private MeetingSyncOutboxRow requireOutbox(MybatisTestDatabase access, String bindingId, String businessKey) {
        return access.mapper(MeetingSyncOutboxSqlMapper.class).selectOne(new LambdaQueryWrapper<MeetingSyncOutboxRow>()
            .eq(MeetingSyncOutboxRow::getBindingId, bindingId)
            .eq(MeetingSyncOutboxRow::getBusinessKey, businessKey));
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
        row.setName("会议主表绑定");
        row.setAppToken("bascn" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        row.setTableId("tbl" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        row.setBusinessType(businessType);
        row.setKeyField("会议唯一键");
        row.setFieldMappingJson("{\"meeting_key\":\"会议唯一键\"}");
        row.setStatus("disabled");
        row.setSchemaRevision("0".repeat(64));
        row.setOwnerUserId(owner);
        row.setConfigJson("{}");
        database.mapper(MeetingBindingMapper.class).insert(row);
        return row;
    }

    private MeetingSyncOutboxRow outbox(String enterprise, String bindingId, String businessKey) {
        return outbox(enterprise, bindingId, businessKey, UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    private MeetingSyncOutboxRow outbox(String enterprise, String bindingId, String businessKey,
                                        String commandId, String createToken) {
        var row = new MeetingSyncOutboxRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setBindingId(bindingId);
        row.setBusinessKey(businessKey);
        row.setCommandId(commandId);
        row.setCreateToken(createToken);
        row.setMaxAttempts(8);
        row.setNextAttemptAt(NOW);
        row.setPayloadJson("{\"会议唯一键\":\"" + businessKey + "\"}");
        return row;
    }

    private String user(MybatisTestDatabase database) {
        var row = new com.stonewu.agenteam.model.user.entity.AppUserRow();
        row.setId(UUID.randomUUID().toString());
        row.setUsername("meeting_sync_" + row.getId());
        row.setUsernameNormalized(row.getUsername());
        row.setPasswordHash("meeting-sync-fixture");
        row.setDisplayName("会议同步结构测试用户");
        database.mapper(com.stonewu.agenteam.mapper.user.AppUserTableMapper.class).insert(row);
        return row.getId();
    }

    private String enterprise(MybatisTestDatabase database, String creator) {
        var row = new EnterpriseRow();
        row.setId(UUID.randomUUID().toString());
        row.setName("会议同步结构测试企业");
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
        row.setDisplayName("会议同步测试成员");
        row.setJoinedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        database.mapper(IdentityQueryMapper.class).insert(row);
    }

    private String connection(MybatisTestDatabase database, String enterprise, String actor) {
        var row = new EnterpriseIntegrationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setProviderCode("feishu");
        row.setName("飞书会议同步测试接入");
        row.setExternalAppId("cli_" + UUID.randomUUID());
        row.setPublicLoginKey(UUID.randomUUID().toString());
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        database.mapper(EnterpriseIntegrationMapper.class).insert(row);
        return row.getId();
    }
}
