package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.NotificationDeliveryWorkAvailableEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers scenario C/D from the Phase 3 continuation spec: the Redis signal only ever fires
 * AFTER commit (proven structurally — see class javadoc — and by the @TransactionalEventListener
 * annotation itself), and a Redis failure here must never propagate.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryRedisSignalerTest {
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private StreamOperations<String, Object, Object> streamOperations;

    private NotificationDeliveryRedisSignaler signaler;

    @BeforeEach
    void setUp() {
        signaler = new NotificationDeliveryRedisSignaler(redisTemplate);
        ReflectionTestUtils.setField(signaler, "streamKey", "notification:delivery:stream");
    }

    @Test
    void doesNothingWhenRedisModeIsDisabled() {
        ReflectionTestUtils.setField(signaler, "redisEnabled", false);

        signaler.onWorkAvailable(new NotificationDeliveryWorkAvailableEvent(2L));

        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void publishesAStreamSignalWhenRedisModeIsEnabled() {
        ReflectionTestUtils.setField(signaler, "redisEnabled", true);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        signaler.onWorkAvailable(new NotificationDeliveryWorkAvailableEvent(2L));

        verify(streamOperations).add(any());
    }

    /**
     * Structural proof (scenario C): the listener is wired to Spring's AFTER_COMMIT phase, not
     * BEFORE_COMMIT or the default (immediate/synchronous) dispatch. This is the actual guarantee
     * that the signal never fires before — or independent of — the publishing transaction's
     * commit; exercising a genuine commit boundary would additionally require a real database
     * transaction, which this environment's Postgres instance is not reachable to provide (see
     * the existing *PostgresIT suite's identical DB_URL-gated limitation). The annotation itself
     * is Spring's own well-tested mechanism — correctly declaring it is what this test proves.
     */
    @Test
    void isWiredToFireOnlyAfterTheEnclosingTransactionCommits() throws NoSuchMethodException {
        Method listener = NotificationDeliveryRedisSignaler.class
                .getMethod("onWorkAvailable", NotificationDeliveryWorkAvailableEvent.class);
        TransactionalEventListener annotation = listener.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void aRedisFailureNeverEscapesTheListener() {
        ReflectionTestUtils.setField(signaler, "redisEnabled", true);
        when(redisTemplate.opsForStream()).thenThrow(new RuntimeException("redis unavailable"));

        // The publishing transaction has already committed by the time this listener runs (see
        // TransactionPhase.AFTER_COMMIT) — an exception here must never surface to the caller,
        // since PostgreSQL already durably holds the PENDING delivery row regardless.
        assertThatCode(() -> signaler.onWorkAvailable(new NotificationDeliveryWorkAvailableEvent(2L)))
                .doesNotThrowAnyException();
    }
}
