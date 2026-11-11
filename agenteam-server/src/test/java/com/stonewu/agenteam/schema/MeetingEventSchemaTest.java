package com.stonewu.agenteam.schema;

import com.stonewu.agenteam.mapper.agent.AgentHireSqlMapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingEventMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSubscriptionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.mapper.test.schema.SchemaSnapshotMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingEventRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
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

/** 在真实隔离数据库验证会议订阅与收件箱的建表、去重、企业外键与后台任务类型。 */
class MeetingEventSchemaTest {
    private static final Set<String> TABLES = Set.of("meeting_subscription", "meeting_event");

    @Test
    void meetingSubscriptionAndEventEnforceDeduplicationAndScoping() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            assertTrue(access.mapper(SchemaSnapshotMapper.class).tables().containsAll(TABLES));
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            String connection = connection(access, enterprise, owner);
            var hire = hire(access, enterprise, owner);

            var subscription = new MeetingSubscriptionRow();
            subscription.setId(UUID.randomUUID().toString());
            subscription.setEnterpriseId(enterprise);
            subscription.setRevision(1L);
            subscription.setCreatedAt(Instant.now());
            subscription.setUpdatedAt(Instant.now());
            subscription.setIntegrationId(connection);
            subscription.setEventType("meeting.advanced_meeting_started");
            subscription.setStatus("enabled");
            subscription.setHireId(hire.id());
            subscription.setAgentVersionId(hire.versionId());
            subscription.setPluginVersion("0.6.0");
            subscription.setOwnerUserId(owner);
            subscription.setConfigJson("{}");
            access.mapper(MeetingSubscriptionMapper.class).insert(subscription);

            var duplicate = new MeetingSubscriptionRow();
            duplicate.setId(UUID.randomUUID().toString());
            duplicate.setEnterpriseId(enterprise);
            duplicate.setRevision(1L);
            duplicate.setCreatedAt(Instant.now());
            duplicate.setUpdatedAt(Instant.now());
            duplicate.setIntegrationId(connection);
            duplicate.setEventType("meeting.advanced_meeting_started");
            duplicate.setStatus("enabled");
            duplicate.setHireId(hire.id());
            duplicate.setAgentVersionId(hire.versionId());
            duplicate.setPluginVersion("0.6.0");
            duplicate.setOwnerUserId(owner);
            duplicate.setConfigJson("{}");
            assertThrows(DataIntegrityViolationException.class, () -> access.mapper(MeetingSubscriptionMapper.class).insert(duplicate));

            var event = event(enterprise, connection, "evt-1");
            access.mapper(MeetingEventMapper.class).insert(event);
            assertThrows(DataIntegrityViolationException.class,
                () -> access.mapper(MeetingEventMapper.class).insert(event(enterprise, connection, "evt-1")));
            access.mapper(MeetingEventMapper.class).insert(event(enterprise, connection, "evt-2"));
            assertEquals(2, access.mapper(MeetingEventMapper.class).selectCount(null));
        }
    }

    @Test
    void backgroundJobAcceptsMeetingEventKindAndStillRejectsUnknownKinds() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            var jobs = access.mapper(BackgroundJobSqlMapper.class);
            String id = UUID.randomUUID().toString();
            jobs.enqueueBackgroundJob(id, enterprise, null, "meeting_event", "meeting-event:" + id, "{}",
                Timestamp.from(Instant.now()));
            assertEquals("meeting_event", jobs.selectById(id).getKind());
            assertThrows(DataAccessException.class,
                () -> jobs.enqueueBackgroundJob(UUID.randomUUID().toString(), enterprise, null, "bogus_kind",
                    "bogus:1", "{}", Timestamp.from(Instant.now())));
        }
    }

    @Test
    void meetingEventAcceptsWaitingStatusAndPersistsWaitUntil() throws Exception {
        try (var database = new IsolatedDatabase()) {
            database.initialize();
            var access = database.databaseAccess();
            String owner = user(access);
            String enterprise = enterprise(access, owner);
            member(access, enterprise, owner);
            String connection = connection(access, enterprise, owner);

            var waiting = event(enterprise, connection, "evt-waiting");
            waiting.setStatus("waiting");
            waiting.setReasonCode("MEETING_ARTIFACT_PENDING");
            waiting.setWaitUntil(Instant.parse("2026-01-01T00:01:00Z"));
            access.mapper(MeetingEventMapper.class).insert(waiting);

            var loaded = access.mapper(MeetingEventMapper.class).selectById(waiting.getId());
            assertEquals("waiting", loaded.getStatus());
            assertEquals(Instant.parse("2026-01-01T00:01:00Z"), loaded.getWaitUntil());

            var invalid = event(enterprise, connection, "evt-bogus");
            invalid.setStatus("bogus");
            assertThrows(DataAccessException.class,
                () -> access.mapper(MeetingEventMapper.class).insert(invalid));
        }
    }

    private MeetingEventRow event(String enterprise, String connection, String eventId) {
        var row = new MeetingEventRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setRevision(1L);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setIntegrationId(connection);
        row.setEventId(eventId);
        row.setEventType("meeting.advanced_meeting_started");
        row.setPayloadJson("{}");
        row.setStatus("received");
        return row;
    }

    private Hire hire(MybatisTestDatabase database, String enterprise, String user) {
        var resource = new ResourceRow();
        resource.setId(UUID.randomUUID().toString());
        resource.setEnterpriseId(enterprise);
        resource.setOwnerUserId(user);
        resource.setKind("agent");
        resource.setName("会议分析智能体");
        resource.setCreatedAt(Instant.now());
        resource.setUpdatedAt(Instant.now());
        database.mapper(ResourceSqlMapper.class).insert(resource);
        var version = new ResourceVersionRow();
        version.setId(UUID.randomUUID().toString());
        version.setEnterpriseId(enterprise);
        version.setResourceId(resource.getId());
        version.setVersionNo(1);
        version.setName(resource.getName());
        version.setConfigJson("{}");
        version.setConfigHash("0".repeat(64));
        version.setReleaseNote("会议订阅结构样本");
        version.setPublishedBy(user);
        version.setPublishedAt(Instant.now());
        database.mapper(ResourceVersionSqlMapper.class).insert(version);
        var hire = new AgentHireRow();
        hire.setId(UUID.randomUUID().toString());
        hire.setEnterpriseId(enterprise);
        hire.setUserId(user);
        hire.setAgentId(resource.getId());
        hire.setHiredAt(Instant.now());
        database.mapper(AgentHireSqlMapper.class).insert(hire);
        return new Hire(hire.getId(), version.getId());
    }

    private String user(MybatisTestDatabase database) {
        var row = new com.stonewu.agenteam.model.user.entity.AppUserRow();
        row.setId(UUID.randomUUID().toString());
        row.setUsername("meeting_" + row.getId());
        row.setUsernameNormalized(row.getUsername());
        row.setPasswordHash("meeting-schema-fixture");
        row.setDisplayName("会议结构测试用户");
        database.mapper(com.stonewu.agenteam.mapper.user.AppUserTableMapper.class).insert(row);
        return row.getId();
    }

    private String enterprise(MybatisTestDatabase database, String creator) {
        var row = new EnterpriseRow();
        row.setId(UUID.randomUUID().toString());
        row.setName("会议结构测试企业");
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
        row.setDisplayName("会议测试成员");
        row.setJoinedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        database.mapper(IdentityQueryMapper.class).insert(row);
    }

    private String connection(MybatisTestDatabase database, String enterprise, String actor) {
        var row = new EnterpriseIntegrationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(enterprise);
        row.setProviderCode("feishu");
        row.setName("飞书会议测试接入");
        row.setExternalAppId("cli_" + UUID.randomUUID());
        row.setPublicLoginKey(UUID.randomUUID().toString());
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        database.mapper(EnterpriseIntegrationMapper.class).insert(row);
        return row.getId();
    }

    private record Hire(String id, String versionId) {
    }
}
