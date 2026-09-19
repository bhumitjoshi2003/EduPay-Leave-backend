package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryRetryCoordinatorTest {
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOperations;

    private NotificationDeliveryRetryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new NotificationDeliveryRetryCoordinator(redisTemplate);
        ReflectionTestUtils.setField(coordinator, "retryZsetKey", "notification:delivery:retry");
        ReflectionTestUtils.setField(coordinator, "redisEnabled", true);
    }

    @Test
    void scheduleRetryAddsTheDeliveryWithTheRetryTimeAsScore() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        coordinator.scheduleRetry(42L, LocalDateTime.now().plusMinutes(5));

        verify(zSetOperations).add(eq("notification:delivery:retry"), eq("42"), anyDouble());
    }

    @Test
    void clearRetryRemovesTheDeliveryFromTheSet() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        coordinator.clearRetry(42L);

        verify(zSetOperations).remove("notification:delivery:retry", "42");
    }

    @Test
    void hasDueRetriesTrueWhenAtLeastOneEntryScoresAtOrBeforeNow() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.rangeByScore(anyString(), anyDouble(), anyDouble(), eq(0L), eq(1L)))
                .thenReturn(Set.of("42"));

        assertThat(coordinator.hasDueRetries()).isTrue();
    }

    @Test
    void hasDueRetriesFalseWhenNothingIsDue() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.rangeByScore(anyString(), anyDouble(), anyDouble(), eq(0L), eq(1L)))
                .thenReturn(Set.of());

        assertThat(coordinator.hasDueRetries()).isFalse();
    }

    @Test
    void allMethodsAreNoOpsWhenRedisModeIsDisabled() {
        ReflectionTestUtils.setField(coordinator, "redisEnabled", false);

        coordinator.scheduleRetry(1L, LocalDateTime.now());
        coordinator.clearRetry(1L);
        boolean due = coordinator.hasDueRetries();
        coordinator.clearDueEntries();

        assertThat(due).isFalse();
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void hasDueRetriesFailsClosedOnARedisError() {
        when(redisTemplate.opsForZSet()).thenThrow(new RuntimeException("redis unavailable"));

        // A Redis hiccup must never be treated as "something is due" — that would force an
        // unnecessary PostgreSQL touch on every failed check, defeating the whole point of
        // checking Redis first.
        assertThat(coordinator.hasDueRetries()).isFalse();
    }

    @Test
    void scheduleRetryFailureNeverThrows() {
        when(redisTemplate.opsForZSet()).thenThrow(new RuntimeException("redis unavailable"));

        coordinator.scheduleRetry(42L, LocalDateTime.now()); // must not throw
    }
}
