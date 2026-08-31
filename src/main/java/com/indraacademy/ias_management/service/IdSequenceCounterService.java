package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.repository.IdSequenceCounterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A separate bean purely so {@link #nextSequence} runs in its own REQUIRES_NEW
 * transaction via Spring's proxy. Calling a @Transactional method from another method on
 * the SAME bean bypasses the proxy entirely (a well-known Spring AOP self-invocation
 * pitfall — the same reason this codebase already keeps @Async methods on a separate bean
 * from their caller), so this can't just be a protected method on IdGeneratorService.
 */
@Service
public class IdSequenceCounterService {

    @Autowired private IdSequenceCounterRepository counterRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long nextSequence(String counterKey, int yearCode) {
        return counterRepository.nextSequence(counterKey, yearCode);
    }
}
