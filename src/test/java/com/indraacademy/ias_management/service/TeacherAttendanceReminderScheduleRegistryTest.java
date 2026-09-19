package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TeacherAttendanceReminderScheduleRegistryTest {

    private final TeacherAttendanceReminderScheduleRegistry registry = new TeacherAttendanceReminderScheduleRegistry();

    @Test
    void putThenCancel_cancelsTheUnderlyingFuture() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        registry.put(1L, future);

        registry.cancel(1L);

        verify(future).cancel(false);
        assertThat(registry.isScheduled(1L)).isFalse();
    }

    @Test
    void puttingANewFutureForTheSameSchool_cancelsThePreviousOne() {
        ScheduledFuture<?> first = mock(ScheduledFuture.class);
        ScheduledFuture<?> second = mock(ScheduledFuture.class);

        registry.put(1L, first);
        registry.put(1L, second);

        verify(first).cancel(false);
        verify(second, never()).cancel(false);
        assertThat(registry.isScheduled(1L)).isTrue();
    }

    @Test
    void cancelOnAnUnscheduledSchool_isANoOp() {
        assertThat(registry.isScheduled(99L)).isFalse();
        registry.cancel(99L); // must not throw
        assertThat(registry.isScheduled(99L)).isFalse();
    }

    @Test
    void removeDropsTheEntryWithoutCancelling() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        registry.put(1L, future);

        registry.remove(1L);

        verify(future, never()).cancel(false);
        assertThat(registry.isScheduled(1L)).isFalse();
    }

    @Test
    void sizeReflectsTheNumberOfScheduledSchools() {
        registry.put(1L, mock(ScheduledFuture.class));
        registry.put(2L, mock(ScheduledFuture.class));

        assertThat(registry.size()).isEqualTo(2);
    }
}
