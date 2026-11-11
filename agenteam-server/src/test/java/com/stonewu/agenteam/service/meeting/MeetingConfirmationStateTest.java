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

    @Test
    void actionMapsToTaskStatus() {
        assertEquals(MeetingTaskStatus.IN_PROGRESS, MeetingConfirmationAction.CONFIRM.taskStatus());
        assertEquals(MeetingTaskStatus.NEEDS_REVISION, MeetingConfirmationAction.REVISE.taskStatus());
        assertEquals(MeetingTaskStatus.REJECTED, MeetingConfirmationAction.REJECT.taskStatus());
    }

    @Test
    void taskStatusJudgesDoneAndUnconfirmed() {
        assertTrue(MeetingTaskStatus.isDone("已完成"));
        assertTrue(MeetingTaskStatus.isDone("completed"));
        assertFalse(MeetingTaskStatus.isDone("进行中"));
        assertTrue(MeetingTaskStatus.isUnconfirmed(null));
        assertTrue(MeetingTaskStatus.isUnconfirmed(""));
        assertTrue(MeetingTaskStatus.isUnconfirmed("待确认"));
        assertFalse(MeetingTaskStatus.isUnconfirmed("进行中"));
        assertEquals("进行中", MeetingTaskStatus.IN_PROGRESS.label());
        assertEquals(MeetingTaskStatus.COMPLETED, MeetingTaskStatus.of("已完成"));
    }
}
