package com.stonewu.agenteam.service.meeting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 确认状态与负责人操作的状态机枚举只做值往返与动作到状态映射校验。 */
class MeetingConfirmationStateTest {

    @Test
    void stateRoundTripsAndRejectsUnknown() {
        for (var state : MeetingConfirmationState.values()) {
            assertEquals(state, MeetingConfirmationState.of(state.value()));
        }
        assertNull(MeetingConfirmationState.of("bogus"));
        assertNull(MeetingConfirmationState.of(null));
    }

    @Test
    void actionMapsToTerminalStateAndRoundTrips() {
        assertEquals(MeetingConfirmationState.CONFIRMED, MeetingConfirmationAction.CONFIRM.state());
        assertEquals(MeetingConfirmationState.NEEDS_REVISION, MeetingConfirmationAction.REVISE.state());
        assertEquals(MeetingConfirmationState.REJECTED, MeetingConfirmationAction.REJECT.state());
        for (var action : MeetingConfirmationAction.values()) {
            assertEquals(action, MeetingConfirmationAction.of(action.value()));
        }
        assertNull(MeetingConfirmationAction.of("bogus"));
        assertNull(MeetingConfirmationAction.of(null));
    }

    @Test
    void statesExposeExpectedValues() {
        assertEquals("pending", MeetingConfirmationState.PENDING.value());
        assertEquals("unresolved", MeetingConfirmationState.UNRESOLVED.value());
        assertEquals("confirm", MeetingConfirmationAction.CONFIRM.value());
        assertEquals("reject", MeetingConfirmationAction.REJECT.value());
    }
}
