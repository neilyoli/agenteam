package com.stonewu.agenteam.service.schedule.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpScanRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleActionRequest;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.meeting.MeetingFollowUpPolicy;
import com.stonewu.agenteam.service.meeting.MeetingFollowUpStore;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 督办操作（meeting.follow_up）的配置校验与扫描作业排入；提交只写数据库，不调用网络或智能体。 */
class MeetingFollowUpActionHandlerTest {
    private static final String BINDING_ID = "binding-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final EnterpriseAuthorizationService authorization = mock(EnterpriseAuthorizationService.class);
    private final MeetingBindingMapper bindings = mock(MeetingBindingMapper.class);
    private final MeetingFollowUpStore store = mock(MeetingFollowUpStore.class);
    private final MeetingFollowUpResultService results = mock(MeetingFollowUpResultService.class);
    private final MeetingFollowUpActionHandler handler =
        new MeetingFollowUpActionHandler(authorization, bindings, store, results, JSON, CLOCK);

    @Test
    void prepareBuildsPolicySnapshotAndForcesZeroRetries() {
        when(bindings.selectOne(any())).thenReturn(taskBinding());

        var definition = handler.prepare(actor(), request(0), null);

        assertEquals("meeting.follow_up", definition.type());
        assertEquals(1, definition.schemaVersion());
        assertEquals(0, definition.maxRetries());
        assertTrue(definition.configJson().contains(BINDING_ID));
    }

    @Test
    void prepareRejectsNonTaskBinding() {
        when(bindings.selectOne(any())).thenReturn(meetingBinding());

        var failure = assertThrows(ApiException.class, () -> handler.prepare(actor(), request(0), null));
        assertEquals("VALIDATION_FAILED", failure.code());
        assertTrue(failure.fieldErrors().containsKey("action.config.bindingId"));
    }

    @Test
    void prepareRejectsNonZeroMaxRetries() {
        when(bindings.selectOne(any())).thenReturn(taskBinding());

        var failure = assertThrows(ApiException.class, () -> handler.prepare(actor(), request(1), null));
        assertEquals("VALIDATION_FAILED", failure.code());
        assertTrue(failure.fieldErrors().containsKey("maxRetries"));
    }

    @Test
    void submitEnqueuesScanAndStaysRunning() throws Exception {
        when(bindings.selectOne(any())).thenReturn(taskBinding());
        var occurrence = new ScheduledOccurrenceRow();
        occurrence.setId("occ-1");
        occurrence.setScheduleId("sched-1");
        occurrence.setActionSnapshotJson(JSON.writeValueAsString(
            new MeetingFollowUpPolicy(BINDING_ID, 1440, 9, 18, Set.of("unconfirmed"))));

        var submission = handler.submit(actor(), occurrence);

        assertEquals("running", submission.status());
        assertTrue(submission.result().path("enqueued").asBoolean(false));

        ArgumentCaptor<MeetingFollowUpScanRow> row = ArgumentCaptor.forClass(MeetingFollowUpScanRow.class);
        verify(store).enqueue(row.capture(), eq(CLOCK.instant()));
        var scan = row.getValue();
        assertEquals("ent", scan.getEnterpriseId());
        assertEquals(BINDING_ID, scan.getBindingId());
        assertEquals("sched-1", scan.getScheduleId());
        assertEquals("occ-1", scan.getOccurrenceId());
        assertEquals("queued", scan.getStatus());
        assertEquals(8, scan.getMaxAttempts());
        assertEquals(0, scan.getAttemptCount());
    }

    private AuthContext actor() {
        var user = new UserEntity("u-1", "user-u-1", "pwd", "显示名", "active", false,
            null, null, null, 0L, null, 0L, null, null);
        return new AuthContext(user, "ent", Set.of("schedule.manage"));
    }

    private ScheduleWriteRequest request(int maxRetries) {
        return new ScheduleWriteRequest("会议督办", null, null, null, "daily", "2026-01-01", "09:00",
            List.of(1), 1, "Asia/Shanghai", true, maxRetries,
            new ScheduleActionRequest("meeting.follow_up", 1, config()));
    }

    private Map<String, Object> config() {
        var config = new LinkedHashMap<String, Object>();
        config.put("bindingId", BINDING_ID);
        config.put("cooldownMinutes", 1440L);
        config.put("windowStartHour", 9);
        config.put("windowEndHour", 18);
        config.put("conditions", List.of("unconfirmed"));
        return config;
    }

    private MeetingBindingRow taskBinding() {
        var row = new MeetingBindingRow();
        row.setId(BINDING_ID);
        row.setEnterpriseId("ent");
        row.setBusinessType("task");
        row.setStatus("enabled");
        return row;
    }

    private MeetingBindingRow meetingBinding() {
        var row = new MeetingBindingRow();
        row.setId(BINDING_ID);
        row.setEnterpriseId("ent");
        row.setBusinessType("meeting");
        row.setStatus("enabled");
        return row;
    }
}
