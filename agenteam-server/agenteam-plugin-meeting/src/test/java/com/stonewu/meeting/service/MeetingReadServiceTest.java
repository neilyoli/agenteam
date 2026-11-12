package com.stonewu.meeting.service;

import com.stonewu.meeting.MeetingError;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MeetingReadServiceTest {

    @Test
    void convertsOffsetDateTimesToUnixSeconds() {
        assertEquals(1793462400L, MeetingReadService.toEpochSeconds("2026-11-01T00:00:00+08:00"));
    }

    @Test
    void defaultsToLastNinetyDays() {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        var range = MeetingReadService.timeRange(null, null, now);
        assertEquals(now.minusSeconds(90L * 24 * 60 * 60), range.start());
        assertEquals(now, range.end());
    }

    @Test
    void rejectsRangeLongerThanNinetyDays() {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        MeetingError error = assertThrows(MeetingError.class, () ->
            MeetingReadService.timeRange("2026-09-01T00:00:00+08:00",
                "2026-12-31T23:59:59+08:00", now));
        assertTrue(error.getMessage().contains("90 天"));
    }

    @Test
    void rejectsInvalidAndReversedTimeRanges() {
        assertThrows(MeetingError.class, () ->
            MeetingReadService.timeRange("2026-11-01", "2026-11-02T00:00:00Z",
                Instant.parse("2026-11-12T00:00:00Z")));
        assertThrows(MeetingError.class, () ->
            MeetingReadService.timeRange("2026-11-03T00:00:00Z", "2026-11-02T00:00:00Z",
                Instant.parse("2026-11-12T00:00:00Z")));
    }
}
