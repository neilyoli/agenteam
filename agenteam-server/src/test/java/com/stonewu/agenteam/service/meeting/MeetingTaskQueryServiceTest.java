package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingTaskIdentityMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingTaskSourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskIdentityRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskSourceRow;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 任务检索：会议范围过滤、来源展示与上游候选排除当前会议。 */
class MeetingTaskQueryServiceTest {
    private static final ResourceJson JSON = new ResourceJson(new ObjectMapper());

    private final MeetingTaskIdentityMapper identities = mock(MeetingTaskIdentityMapper.class);
    private final MeetingTaskSourceMapper sources = mock(MeetingTaskSourceMapper.class);
    private final MeetingBindingMapper bindings = mock(MeetingBindingMapper.class);
    private final MeetingBitableClient bitable = mock(MeetingBitableClient.class);
    private final IntegrationQueryService integrations = mock(IntegrationQueryService.class);
    private final IntegrationTokenService tokens = mock(IntegrationTokenService.class);
    private final MeetingTaskQueryService service = new MeetingTaskQueryService(identities, sources, bindings,
        bitable, integrations, tokens, JSON);

    @Test
    void searchByMeetingReturnsEmptyWhenNoSources() {
        when(sources.selectList(any())).thenReturn(List.of());
        var result = service.search("ent", null, null, "mtg-none", null, false, 10);
        assertEquals(0, result.path("items").size());
        assertFalse(result.path("isError").asBoolean());
    }

    @Test
    void searchByTaskKeyReturnsSourcesWithoutLiveStatus() {
        when(identities.find("ent", "T-1")).thenReturn(identity("T-1", "对接方案", "ou_a"));
        when(sources.listByTask("ent", "T-1")).thenReturn(List.of(source("T-1", "mtg-1"), source("T-1", "mtg-2")));

        var result = service.search("ent", "T-1", null, null, null, false, 10);
        assertEquals(1, result.path("items").size());
        var item = result.path("items").get(0);
        assertEquals("T-1", item.path("taskKey").asText());
        assertEquals(2, item.path("sources").size());
        assertTrue(item.path("live").isMissingNode());
        verify(bitable, never()).findRecordFields(any(), any(), any(), any(), any(), any());
    }

    @Test
    void upstreamSearchExcludesTasksOnlyMentionedInCurrentMeeting() {
        var onlyCurrent = identity("T-current-only", "当前会议任务", "ou_a");
        var crossMeeting = identity("T-cross", "跨会议任务", "ou_b");
        when(identities.selectList(any())).thenReturn(List.of(onlyCurrent, crossMeeting));
        when(sources.listByTask("ent", "T-current-only")).thenReturn(List.of(source("T-current-only", "mtg-now")));
        when(sources.listByTask("ent", "T-cross")).thenReturn(List.of(source("T-cross", "mtg-earlier")));

        var result = service.upstreamSearch("ent", "mtg-now", null, null, 10);
        assertEquals(1, result.path("items").size());
        assertEquals("T-cross", result.path("items").get(0).path("taskKey").asText());
    }

    @Test
    void upstreamSearchReturnsEmptyHonestlyWhenNothingFound() {
        when(identities.selectList(any())).thenReturn(List.of());
        var result = service.upstreamSearch("ent", "mtg-now", "关键词", null, 10);
        assertEquals(0, result.path("items").size());
        assertFalse(result.path("isError").asBoolean());
    }

    private MeetingTaskIdentityRow identity(String key, String title, String ownerOpenId) {
        var row = new MeetingTaskIdentityRow();
        row.setId(key);
        row.setEnterpriseId("ent");
        row.setTitleCurrent(title);
        row.setTitleNormalized(title);
        row.setOwnerOpenId(ownerOpenId);
        row.setSourceCount(1);
        row.setStatus("active");
        return row;
    }

    private MeetingTaskSourceRow source(String taskKey, String meetingId) {
        var row = new MeetingTaskSourceRow();
        row.setId("src-" + taskKey + "-" + meetingId);
        row.setEnterpriseId("ent");
        row.setTaskKey(taskKey);
        row.setMeetingId(meetingId);
        return row;
    }
}
