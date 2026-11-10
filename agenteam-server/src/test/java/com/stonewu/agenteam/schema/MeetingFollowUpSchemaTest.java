package com.stonewu.agenteam.schema;

import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingFollowUpMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingFollowUpScanMapper;
import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpScanRow;
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

/** 在真实隔离数据库验证督办台账与扫描作业的建表、唯一键、检查约束，以及限频与领取栅栏。 */
class MeetingFollowUpSchemaTest {
    private static final Set<String> TABLES = Set.of("meeting_follow_up", "meeting_follow_up_scan");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void followUpTablesEnforceUniquenessChecksAndForeignKeys() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            assertTrue(access.mapper(SchemaSnapshotMapper.class).tables().containsAll(TABLES));

            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            String connection = connection(access, enterprise, owner);
            var binding = binding(access, enterprise, connection, owner, "task");
            var ledgers = access.mapper(MeetingFollowUpMapper.class);
            var scans = access.mapper(MeetingFollowUpScanMapper.class);

            ledgers.insert(ledger(enterprise, binding.getId(), "task-1"));
            assertThrows(DataIntegrityViolationException.class,
                () -> ledgers.insert(ledger(enterprise, binding.getId(), "task-1")));

            var badStatus = ledger(enterprise, binding.getId(), "task-bad-status");
            badStatus.setStatus("bogus");
            assertThrows(DataAccessException.class, () -> ledgers.insert(badStatus));

            var badRevision = ledger(enterprise, binding.getId(), "task-bad-revision");
            badRevision.setRevision(0L);
            assertThrows(DataAccessException.class, () -> ledgers.insert(badRevision));

            var missingBinding = ledger(enterprise, "missing-binding", "task-no-binding");
            assertThrows(DataIntegrityViolationException.class, () -> ledgers.insert(missingBinding));

            var badScanStatus = scan(enterprise, binding.getId(), "occ-1");
            badScanStatus.setStatus("bogus");
            assertThrows(DataAccessException.class, () -> scans.insert(badScanStatus));

            var missingScanBinding = scan(enterprise, "missing-binding", "occ-2");
            assertThrows(DataIntegrityViolationException.class, () -> scans.insert(missingScanBinding));
        }
    }

    @Test
    void remindFenceRateLimitsAndRecordSentCounts() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            var binding = binding(access, enterprise, connection(access, enterprise, owner), owner, "task");
            var ledgers = access.mapper(MeetingFollowUpMapper.class);

            ledgers.insert(ledger(enterprise, binding.getId(), "task-1"));
            // 首催抢占：next_reminder_at 为空即可催办，置为 now+1 天
            assertEquals(1, ledgers.remind(enterprise, binding.getId(), "task-1", ts(NOW), ts(NOW.plus(1, ChronoUnit.DAYS)), "{}"));
            var fenced = ledgers.find(enterprise, binding.getId(), "task-1");
            assertEquals(NOW.plus(1, ChronoUnit.DAYS), fenced.getNextReminderAt());
            assertEquals("{}", fenced.getLastEvidenceJson());

            // 冷却期内不再催办
            assertEquals(0, ledgers.remind(enterprise, binding.getId(), "task-1", ts(NOW), ts(NOW.plus(1, ChronoUnit.DAYS)), "{}"));

            // 催办成功后回填时间与累计次数
            assertEquals(1, ledgers.recordSent(enterprise, binding.getId(), "task-1", ts(NOW)));
            var sent = ledgers.find(enterprise, binding.getId(), "task-1");
            assertEquals(NOW, sent.getLastReminderAt());
            assertEquals(1, sent.getReminderCount());

            // 超过冷却期后可再次催办
            assertEquals(1, ledgers.remind(enterprise, binding.getId(), "task-1", ts(NOW.plus(2, ChronoUnit.DAYS)),
                ts(NOW.plus(3, ChronoUnit.DAYS)), "{\"task_key\":\"task-1\"}"));
        }
    }

    @Test
    void scanClaimCompleteAndCancelFencing() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            var binding = binding(access, enterprise, connection(access, enterprise, owner), owner, "task");
            var scans = access.mapper(MeetingFollowUpScanMapper.class);

            var scan = scan(enterprise, binding.getId(), "occ-1");
            scans.insert(scan);
            assertEquals(1, scans.claim(enterprise, scan.getId(), "tok-1", ts(NOW.plusSeconds(120)), ts(NOW)));
            var claimed = scans.selectById(scan.getId());
            assertEquals(1, claimed.getAttemptCount());
            assertEquals("tok-1", claimed.getClaimToken());

            // 令牌不匹配的回写被栅栏拒绝
            assertEquals(0, scans.complete(enterprise, scan.getId(), "wrong", "{}", ts(NOW)));
            assertEquals(1, scans.complete(enterprise, scan.getId(), "tok-1", "{\"reminded\":1}", ts(NOW)));
            var completed = scans.selectById(scan.getId());
            assertEquals("completed", completed.getStatus());
            // JSON 列由 MySQL 归一化空白，只断言摘要键已保存
            assertTrue(completed.getResultJson().contains("\"reminded\""));

            // 已完成作业不再领取
            assertEquals(0, scans.claim(enterprise, scan.getId(), "tok-2", ts(NOW.plusSeconds(120)), ts(NOW)));

            // 发生记录取消时阻断待处理扫描作业
            var cancel = scan(enterprise, binding.getId(), "occ-cancel");
            scans.insert(cancel);
            assertEquals(1, scans.cancelByOccurrence(enterprise, "occ-cancel", ts(NOW)));
            var blocked = scans.selectById(cancel.getId());
            assertEquals("blocked", blocked.getStatus());
            assertEquals("CANCELLED", blocked.getLastErrorCode());

            // 达重试上限的排队作业被标记失败
            var exhausted = scan(enterprise, binding.getId(), "occ-exhausted");
            exhausted.setAttemptCount(8);
            scans.insert(exhausted);
            assertEquals(1, scans.markExhausted(ts(NOW)));
            assertEquals("failed", scans.selectById(exhausted.getId()).getStatus());
        }
    }

    private MeetingFollowUpRow ledger(String enterprise, String bindingId, String businessKey) {
        var row = new MeetingFollowUpRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setRevision(1L);
        row.setCreatedAt(NOW);
        row.setUpdatedAt(NOW);
        row.setBindingId(bindingId);
        row.setBusinessKey(businessKey);
        row.setOwnerSubject("ou_owner_1");
        row.setReminderCount(0);
        row.setStatus("active");
        return row;
    }

    private MeetingFollowUpScanRow scan(String enterprise, String bindingId, String occurrenceId) {
        var row = new MeetingFollowUpScanRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setRevision(1L);
        row.setCreatedAt(NOW);
        row.setUpdatedAt(NOW);
        row.setBindingId(bindingId);
        row.setScheduleId("sched-1");
        row.setOccurrenceId(occurrenceId);
        row.setPolicyJson("{}");
        row.setStatus("queued");
        row.setAttemptCount(0);
        row.setMaxAttempts(8);
        row.setNextAttemptAt(NOW);
        return row;
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
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
        row.setKeyField("任务唯一键");
        row.setFieldMappingJson("{\"task_key\":\"任务唯一键\"}");
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
        row.setUsername("meeting_follow_up_" + row.getId());
        row.setUsernameNormalized(row.getUsername());
        row.setPasswordHash("meeting-follow-up-fixture");
        row.setDisplayName("会议督办结构测试用户");
        database.mapper(com.stonewu.agenteam.mapper.user.AppUserTableMapper.class).insert(row);
        return row.getId();
    }

    private String enterprise(MybatisTestDatabase database, String creator) {
        var row = new EnterpriseRow();
        row.setId(UUID.randomUUID().toString());
        row.setName("会议督办结构测试企业");
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
        row.setDisplayName("会议督办测试成员");
        row.setJoinedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        database.mapper(IdentityQueryMapper.class).insert(row);
    }

    private String connection(MybatisTestDatabase database, String enterprise, String actor) {
        var row = new EnterpriseIntegrationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setProviderCode("feishu");
        row.setName("飞书会议督办测试接入");
        row.setExternalAppId("cli_" + UUID.randomUUID());
        row.setPublicLoginKey(UUID.randomUUID().toString());
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        database.mapper(EnterpriseIntegrationMapper.class).insert(row);
        return row.getId();
    }
}
