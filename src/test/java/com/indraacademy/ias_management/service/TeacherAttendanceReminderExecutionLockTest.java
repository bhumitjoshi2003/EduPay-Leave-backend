package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherAttendanceReminderExecutionLockTest {

    @Mock private Connection connection;
    @Mock private PreparedStatement acquireStatement;
    @Mock private PreparedStatement releaseStatement;
    @Mock private ResultSet resultSet;

    private final TeacherAttendanceReminderExecutionLock lock = new TeacherAttendanceReminderExecutionLock();

    @Test
    void lockKeyIsDeterministicForTheSameSchoolAndDate() {
        long key1 = TeacherAttendanceReminderExecutionLock.lockKey(1L, LocalDate.of(2026, 9, 17));
        long key2 = TeacherAttendanceReminderExecutionLock.lockKey(1L, LocalDate.of(2026, 9, 17));
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void lockKeyDiffersAcrossSchoolsAndDates() {
        long a = TeacherAttendanceReminderExecutionLock.lockKey(1L, LocalDate.of(2026, 9, 17));
        long b = TeacherAttendanceReminderExecutionLock.lockKey(2L, LocalDate.of(2026, 9, 17));
        long c = TeacherAttendanceReminderExecutionLock.lockKey(1L, LocalDate.of(2026, 9, 18));

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void tryAcquire_returnsTrueWhenPostgresGrantsTheLock() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(acquireStatement);
        when(acquireStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);

        boolean acquired = lock.tryAcquire(connection, 1L, LocalDate.of(2026, 9, 17));

        assertThat(acquired).isTrue();
    }

    @Test
    void tryAcquire_returnsFalseWhenAnotherInstanceAlreadyOwnsIt() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(acquireStatement);
        when(acquireStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(false);

        boolean acquired = lock.tryAcquire(connection, 1L, LocalDate.of(2026, 9, 17));

        assertThat(acquired).isFalse();
    }

    @Test
    void release_neverThrowsEvenWhenTheStatementFails() throws SQLException {
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("connection closed"));

        assertThatCode(() -> lock.release(connection, 1L, LocalDate.of(2026, 9, 17))).doesNotThrowAnyException();
    }

    @Test
    void release_executesTheUnlockStatement() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(releaseStatement);

        lock.release(connection, 1L, LocalDate.of(2026, 9, 17));

        verify(releaseStatement).execute();
    }
}
