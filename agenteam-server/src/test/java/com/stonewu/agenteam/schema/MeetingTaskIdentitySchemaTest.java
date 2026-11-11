package com.stonewu.agenteam.schema;

import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingTaskIdentityMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingTaskSourceMapper;
import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskIdentityRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskSourceRow;
import com.stonewu.agenteam.support.IsolatedDatabase;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实隔离数据库验证任务身份与来源表结构、唯一键、检查约束与外键链。 */
class MeetingTaskIdentitySchemaTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void identityTableEnforcesUniquenessChecksAndForeignKeys() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            assertTrue(access.mapper(SchemaSnapshotMapper.class).tables()
                .containsAll(java.util.Set.of("meeting_task_identity", "meeting_task_source")));

            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            var binding = binding(access, enterprise, connection(access, enterprise, owner), owner);

            var identities = access.mapper(MeetingTaskIdentityMapper.class);
            var row = identity(enterprise, binding.getId(), "T-abc123");
            identities.insert(row);
            // 同一企业同任务键唯一。
            assertThrows(DataIntegrityViolationException.class,
                () -> identities.insert(identity(enterprise, binding.getId(), "T-abc123")));
            // 非法状态与来源计数被 CHECK 拒绝。
            var badStatus = identity(enterprise, binding.getId(), "T-bad-status");
            badStatus.setStatus("bogus");
            assertThrows(DataAccessException.class, () -> identities.insert(badStatus));
            var badCount = identity(enterprise, binding.getId(), "T-bad-count");
            badCount.setSourceCount(0);
            assertThrows(DataAccessException.class, () -> identities.insert(badCount));
            // 绑定必须属于同一企业。
            assertThrows(DataIntegrityViolationException.class,
                () -> identities.insert(identity(enterprise, "missing-binding", "T-no-binding")));
        }
    }

    @Test
    void sourceTableEnforcesTaskAndMeetingUniqueness() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            var binding = binding(access, enterprise, connection(access, enterprise, owner), owner);
            var identities = access.mapper(MeetingTaskIdentityMapper.class);
            var sources = access.mapper(MeetingTaskSourceMapper.class);

            identities.insert(identity(enterprise, binding.getId(), "T-task-1"));
            sources.insert(source(enterprise, "T-task-1", "mtg-1"));
            // 同一任务同一会议只有一条来源；跨会议再次提及是新的来源记录。
            assertThrows(DataIntegrityViolationException.class,
                () -> sources.insert(source(enterprise, "T-task-1", "mtg-1")));
            sources.insert(source(enterprise, "T-task-1", "mtg-2"));
            assertEquals(2, sources.listByTask(enterprise, "T-task-1").size());
            // 来源必须引用真实存在的任务身份。
            assertThrows(DataIntegrityViolationException.class,
                () -> sources.insert(source(enterprise, "T-missing", "mtg-1")));
        }
    }

    private MeetingTaskIdentityRow identity(String enterprise, String bindingId, String taskKey) {
        var row = new MeetingTaskIdentityRow();
        row.setId(taskKey);
        row.setEnterpriseId(enterprise);
        row.setRevision(1L);
        row.setCreatedAt(NOW);
        row.setUpdatedAt(NOW);
        row.setBindingId(bindingId);
        row.setMeetingId("mtg-1");
        row.setTitleCurrent("落地对接方案");
        row.setTitleNormalized("落地对接方案");
        row.setOwnerOpenId("ou_owner");
        row.setOwnerUserId(null);
        row.setOwnerName("张三");
        row.setSourceCount(1);
        row.setStatus("active");
        row.setCreatedRunId(null);
        row.setCreatedCommandId("cmd-1");
        return row;
    }

    private MeetingTaskSourceRow source(String enterprise, String taskKey, String meetingId) {
        var row = new MeetingTaskSourceRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setTaskKey(taskKey);
        row.setRevision(1L);
        row.setCreatedAt(NOW);
        row.setUpdatedAt(NOW);
        row.setMeetingId(meetingId);
        row.setMeetingTitle("季度经营会");
        row.setRunId(null);
        row.setCommandId("cmd-1");
        row.setEvidenceJson("{\"items\":[{\"sourceType\":\"minutes\",\"anchor\":\"p1\"}]}");
        return row;
    }

    private MeetingBindingRow binding(MybatisTestDatabase database, String enterprise, String connection,
                                      String owner) {
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
        row.setUsername("meeting_identity_" + row.getId());
        row.setUsernameNormalized(row.getUsername());
        row.setPasswordHash("meeting-identity-fixture");
        row.setDisplayName("会议身份结构测试用户");
        database.mapper(com.stonewu.agenteam.mapper.user.AppUserTableMapper.class).insert(row);
        return row.getId();
    }

    private String enterprise(MybatisTestDatabase database, String creator) {
        var row = new EnterpriseRow();
        row.setId(UUID.randomUUID().toString());
        row.setName("会议任务身份结构测试企业");
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
        row.setDisplayName("会议身份测试成员");
        row.setJoinedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        database.mapper(IdentityQueryMapper.class).insert(row);
    }

    private String connection(MybatisTestDatabase database, String enterprise, String actor) {
        var row = new EnterpriseIntegrationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setProviderCode("feishu");
        row.setName("飞书任务身份测试接入");
        row.setExternalAppId("cli_" + UUID.randomUUID());
        row.setPublicLoginKey(UUID.randomUUID().toString());
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        database.mapper(EnterpriseIntegrationMapper.class).insert(row);
        return row.getId();
    }
}
