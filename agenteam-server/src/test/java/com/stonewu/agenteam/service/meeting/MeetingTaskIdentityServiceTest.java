package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.mapper.meeting.MeetingTaskIdentityMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingTaskSourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskIdentityRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskSourceRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 稳定任务身份：宿主分配持久身份、跨会议复用、重复候选提示与上游引用校验。 */
class MeetingTaskIdentityServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final ResourceJson JSON = new ResourceJson(new ObjectMapper());

    private final MeetingTaskIdentityMapper identities = mock(MeetingTaskIdentityMapper.class);
    private final MeetingTaskSourceMapper sources = mock(MeetingTaskSourceMapper.class);
    private final MeetingTaskIdentityService service =
        new MeetingTaskIdentityService(identities, sources, JSON, CLOCK);

    @Test
    void assignsNewStableIdentityWhenNoDuplicate() {
        when(identities.findActiveByTitleAndOwner("ent", "落地对接方案", "ou_a")).thenReturn(List.of());

        String key = service.resolve("ent", "binding-1", request(null, "落地对接方案", "ou_a", "mtg-1"));

        assertTrue(key.startsWith("T-"));
        ArgumentCaptor<MeetingTaskIdentityRow> captor = ArgumentCaptor.forClass(MeetingTaskIdentityRow.class);
        verify(identities).insert(captor.capture());
        assertEquals(key, captor.getValue().getId());
        assertEquals("落地对接方案", captor.getValue().getTitleCurrent());
        assertEquals("落地对接方案", captor.getValue().getTitleNormalized());
        assertEquals(0, captor.getValue().getSourceCount());
        verify(sources).insert(any(MeetingTaskSourceRow.class));
    }

    @Test
    void rejectsDuplicateTitleAndOwnerWithoutExplicitReference() {
        when(identities.findActiveByTitleAndOwner("ent", "落地对接方案", "ou_a"))
            .thenReturn(List.of(identity("T-existing-1", "落地对接方案", "ou_a")));

        var failure = assertThrows(ApiException.class,
            () -> service.resolve("ent", "binding-1", request(null, "落地对接方案", "ou_a", "mtg-1")));
        assertEquals("MEETING_TASK_DUPLICATE_CANDIDATE", failure.code());
        assertTrue(failure.getReason().contains("T-existing-1"));
        verify(identities, never()).insert(any(MeetingTaskIdentityRow.class));
    }

    @Test
    void reusesExistingIdentityAcrossMeetings() {
        var existing = identity("T-stable", "落地对接方案", "ou_a");
        when(identities.find("ent", "T-stable")).thenReturn(existing);
        when(sources.find("ent", "T-stable", "mtg-2")).thenReturn(null);

        String key = service.resolve("ent", "binding-1", request("T-stable", "落地对接方案（修订）", "ou_a", "mtg-2"));

        assertEquals("T-stable", key);
        // 身份键不变，展示快照更新。
        ArgumentCaptor<MeetingTaskIdentityRow> captor = ArgumentCaptor.forClass(MeetingTaskIdentityRow.class);
        verify(identities, atLeastOnce()).updateById(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(row -> "T-stable".equals(row.getId())));
        assertTrue(captor.getAllValues().stream()
            .anyMatch(row -> "落地对接方案（修订）".equals(row.getTitleCurrent())));
        verify(sources).insert(any(MeetingTaskSourceRow.class));
    }

    @Test
    void updatesSourceWhenSameMeetingMentionsAgain() {
        var existing = identity("T-stable", "落地对接方案", "ou_a");
        existing.setSourceCount(1);
        when(identities.find("ent", "T-stable")).thenReturn(existing);
        var source = new MeetingTaskSourceRow();
        source.setMeetingId("mtg-1");
        when(sources.find("ent", "T-stable", "mtg-1")).thenReturn(source);

        String key = service.resolve("ent", "binding-1", request("T-stable", "落地对接方案", "ou_a", "mtg-1"));

        assertEquals("T-stable", key);
        verify(sources, never()).insert(any(MeetingTaskSourceRow.class));
        verify(sources).updateById(source);
        // 同一会议重复提及不增加来源计数。
        assertEquals(1, existing.getSourceCount());
    }

    @Test
    void rejectsUnknownOrMergedReferences() {
        when(identities.find("ent", "T-missing")).thenReturn(null);
        var failure = assertThrows(ApiException.class,
            () -> service.resolve("ent", "binding-1", request("T-missing", "标题", "ou_a", "mtg-1")));
        assertEquals("MEETING_TASK_NOT_FOUND", failure.code());

        var merged = identity("T-merged", "标题", "ou_a");
        merged.setStatus("merged");
        when(identities.find("ent", "T-merged")).thenReturn(merged);
        var mergedFailure = assertThrows(ApiException.class,
            () -> service.resolve("ent", "binding-1", request("T-merged", "标题", "ou_a", "mtg-1")));
        assertEquals("MEETING_TASK_MERGED", mergedFailure.code());
    }

    @Test
    void validatesUpstreamReferences() {
        when(identities.find("ent", "T-real")).thenReturn(identity("T-real", "上游任务", "ou_b"));
        when(identities.find("ent", "T-unknown")).thenReturn(null);

        assertDoesNotThrow(() -> service.requireUpstreamReferences("ent", List.of("T-real")));
        var failure = assertThrows(ApiException.class,
            () -> service.requireUpstreamReferences("ent", List.of("T-real", "T-unknown")));
        assertEquals("MEETING_UPSTREAM_TASK_NOT_FOUND", failure.code());
        assertTrue(failure.getReason().contains("T-unknown"));
    }

    private MeetingTaskIdentityService.IdentityRequest request(String existingKey, String title, String ownerOpenId,
                                                               String meetingId) {
        return new MeetingTaskIdentityService.IdentityRequest(existingKey, title, ownerOpenId, "user-a", "张三",
            meetingId, "季度会", List.of(Map.of("sourceType", "minutes", "anchor", "p1")), "run-1", "cmd-1");
    }

    private MeetingTaskIdentityRow identity(String key, String title, String ownerOpenId) {
        var row = new MeetingTaskIdentityRow();
        row.setId(key);
        row.setEnterpriseId("ent");
        row.setBindingId("binding-1");
        row.setTitleCurrent(title);
        row.setTitleNormalized(title);
        row.setOwnerOpenId(ownerOpenId);
        row.setSourceCount(1);
        row.setStatus("active");
        return row;
    }
}
