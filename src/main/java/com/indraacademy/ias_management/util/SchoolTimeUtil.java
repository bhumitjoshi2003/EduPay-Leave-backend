package com.indraacademy.ias_management.util;

import com.indraacademy.ias_management.entity.School;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * The one place "what timezone is this school in" is resolved, with the same defensive
 * fallback everywhere it's used — extracted from TeacherAttendanceService when TeacherLeave
 * needed the identical rule (a leave date range and an attendance date must agree on what
 * "today" means, or a same-day leave application and check-in could disagree about which
 * calendar day they're each looking at).
 */
public final class SchoolTimeUtil {

    private static final Logger log = LoggerFactory.getLogger(SchoolTimeUtil.class);
    public static final String DEFAULT_TIMEZONE = "Asia/Kolkata";

    private SchoolTimeUtil() {}

    /** The school's configured IANA zone, defaulting defensively if unset or invalid. */
    public static ZoneId zoneId(School school) {
        String tz = school != null ? school.getTimezone() : null;
        if (tz == null || tz.isBlank()) return ZoneId.of(DEFAULT_TIMEZONE);
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            log.warn("School {} has an invalid timezone '{}', falling back to {}",
                    school.getId(), tz, DEFAULT_TIMEZONE);
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }
}
