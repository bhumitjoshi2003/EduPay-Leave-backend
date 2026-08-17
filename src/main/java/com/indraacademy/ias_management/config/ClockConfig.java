package com.indraacademy.ias_management.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injectable {@link Clock}, so "now" is a dependency rather than a hardcoded static
 * call. TeacherAttendanceService re-zones this clock per school (via {@code clock.withZone(...)})
 * instead of calling the zone-less {@code LocalDate.now()}/{@code LocalTime.now()}, which read
 * the application server's own timezone — silently wrong for a school in a different zone.
 *
 * <p>UTC is the base; re-zoning happens at the point of use, never here. This also makes the
 * service's time-dependent behavior deterministically testable by injecting a fixed {@link Clock}
 * in tests instead of depending on the real wall clock at whatever moment the test happens to run.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
