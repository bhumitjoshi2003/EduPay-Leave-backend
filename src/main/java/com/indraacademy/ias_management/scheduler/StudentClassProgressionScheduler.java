package com.indraacademy.ias_management.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Retired in E3. Membership progression is recorded explicitly through the
 * year-end API and activated from authoritative enrollment history.
 * This bean has no scheduled entry point and no persistence dependencies.
 */
@Service
public class StudentClassProgressionScheduler {
    private static final Logger log = LoggerFactory.getLogger(StudentClassProgressionScheduler.class);

    public void reportRetired() {
        log.info("Automatic Student class progression is retired; use explicit year-end decisions");
    }
}
