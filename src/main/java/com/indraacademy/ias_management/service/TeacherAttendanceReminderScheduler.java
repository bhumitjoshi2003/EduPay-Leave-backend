package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolHoliday;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherAttendance;
import com.indraacademy.ias_management.entity.TeacherAttendanceSchedule;
import com.indraacademy.ias_management.entity.TeacherLeave;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.repository.SchoolHolidayRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.TeacherAttendanceRepository;
import com.indraacademy.ias_management.repository.TeacherLeaveRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import com.indraacademy.ias_management.util.TeacherWorkingDayUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A single scanner (not one job per school) that, every 5 minutes, checks every active school
 * with {@code teacherAttendanceReminderEnabled} for whether that school's configured local
 * reminder time has just been reached, and if so sends a one-time same-day reminder to every
 * teacher who is expected to work today but hasn't marked attendance and isn't on approved leave.
 *
 * <h2>Catch-up window</h2>
 * The scheduler does not need to run at the exact configured minute — a school-local time inside
 * {@code [reminderTime, reminderTime + CATCH_UP_WINDOW)} is treated as "due now". At the default
 * 5-minute tick this window only needs to be wide enough to absorb scheduling jitter and a short
 * JVM restart; {@link #CATCH_UP_WINDOW} is 30 minutes for v1. A reminder missed entirely past
 * that window is intentionally never sent late (e.g. a 7:45 AM reminder does not fire at 2 PM).
 *
 * <h2>Idempotency</h2>
 * Delivery is deduplicated by {@link BusinessNotificationService}'s existing tenant-scoped
 * idempotency key (a partial unique index on {@code (school_id, idempotency_key)} — see
 * V48__notification_domain_and_delivery_outbox.sql), using the key
 * {@code teacher-attendance-reminder:<schoolId>:<localDate>:<teacherId>}. This is what makes it
 * safe for this method to run every 5 minutes, for the whole catch-up window to re-evaluate
 * already-reminded teachers, and for a restart to re-run the same tick without ever producing a
 * second notification for the same teacher/date.
 */
@Service
@RequiredArgsConstructor
public class TeacherAttendanceReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TeacherAttendanceReminderScheduler.class);

    /** v1 catch-up window: how long after the configured time a reminder may still fire. */
    static final Duration CATCH_UP_WINDOW = Duration.ofMinutes(30);

    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SchoolHolidayRepository schoolHolidayRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TeacherAttendanceRepository teacherAttendanceRepository;
    @Autowired private TeacherLeaveRepository teacherLeaveRepository;
    @Autowired private TeacherAttendanceScheduleService teacherAttendanceScheduleService;
    @Autowired private BusinessNotificationService businessNotifications;
    @Autowired private Clock clock;

    @Scheduled(cron = "0 */5 * * * *")
    public void sendTeacherAttendanceReminders() {
        List<School> candidateSchools = schoolRepository.findAll().stream()
                .filter(School::isActive)
                .filter(School::isTeacherAttendanceReminderEnabled)
                .collect(Collectors.toList());
        if (candidateSchools.isEmpty()) return;

        int dueSchools = 0;
        for (School school : candidateSchools) {
            try {
                if (processSchool(school)) dueSchools++;
            } catch (Exception e) {
                // One school's failure (bad data, a downstream error) must never stop the run
                // for the rest — each school is evaluated and reminded independently.
                log.error("Teacher attendance reminder run failed for schoolId={}: {}",
                        school.getId(), e.getMessage(), e);
            }
        }
        if (dueSchools > 0) {
            log.info("Teacher attendance reminder run: {} of {} enabled school(s) were due this tick.",
                    dueSchools, candidateSchools.size());
        }
    }

    /** Returns true iff the school's reminder time was due this tick (regardless of how many,
     * if any, teachers actually ended up eligible) — used only for the summary log above. */
    private boolean processSchool(School school) {
        LocalTime reminderTime = school.getTeacherAttendanceReminderTime();
        if (reminderTime == null) {
            log.warn("School {} has the teacher attendance reminder enabled but no reminder time configured — skipping.",
                    school.getId());
            return false;
        }

        ZoneId zone = SchoolTimeUtil.zoneId(school);
        ZonedDateTime nowZoned = ZonedDateTime.now(clock.withZone(zone));
        LocalDate today = nowZoned.toLocalDate();
        LocalTime nowTime = nowZoned.toLocalTime();

        if (!isDueNow(reminderTime, nowTime)) return false;

        if (school.getStaffAttendanceTrackingStartDate() != null
                && today.isBefore(school.getStaffAttendanceTrackingStartDate())) {
            return false;
        }

        Long schoolId = school.getId();
        List<Teacher> activeTeachers = teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, schoolId);
        if (activeTeachers.isEmpty()) return true;

        List<String> teacherIds = activeTeachers.stream().map(Teacher::getTeacherId).toList();
        Set<String> activeUserIds = userRepository.findBySchoolIdAndActiveTrueAndUserIdIn(schoolId, teacherIds)
                .stream().map(User::getUserId).collect(Collectors.toSet());

        List<SchoolHoliday> holidays = schoolHolidayRepository.findOverlapping(schoolId, today, today);
        List<TeacherLeave> approvedLeaves = teacherLeaveRepository.findApprovedOverlapping(schoolId, today, today);
        Set<String> teachersWithAttendance = teacherAttendanceRepository.findBySchoolIdAndDate(schoolId, today)
                .stream().map(TeacherAttendance::getTeacherId).collect(Collectors.toSet());
        Map<String, List<TeacherAttendanceSchedule>> schedules =
                teacherAttendanceScheduleService.schedulesByTeacher(schoolId, today, today);

        for (Teacher teacher : activeTeachers) {
            remindIfEligible(school, teacher, today, activeUserIds, holidays, approvedLeaves,
                    teachersWithAttendance, schedules);
        }
        return true;
    }

    private void remindIfEligible(School school, Teacher teacher, LocalDate today, Set<String> activeUserIds,
                                   List<SchoolHoliday> holidays, List<TeacherLeave> approvedLeaves,
                                   Set<String> teachersWithAttendance,
                                   Map<String, List<TeacherAttendanceSchedule>> schedules) {
        String teacherId = teacher.getTeacherId();
        Long schoolId = school.getId();

        if (!activeUserIds.contains(teacherId)) return;               // no active login recipient
        if (teachersWithAttendance.contains(teacherId)) return;       // any record — manual or GPS — counts
        if (TeacherWorkingDayUtil.isCoveredByApprovedLeave(teacherId, today, approvedLeaves)) return;

        String workingDays = teacherAttendanceScheduleService.workingDaysFor(
                teacherId, today, school.getWorkingDays(), schedules);
        if (!TeacherWorkingDayUtil.isWorkingDay(today, workingDays, holidays)) return; // holiday or not a working day

        try {
            businessNotifications.direct(schoolId, teacherId, NotificationEventCode.ATTENDANCE_REMINDER,
                    NotificationCategory.ATTENDANCE, "Attendance reminder",
                    "Your attendance has not been marked yet. Please mark your attendance or apply for leave if you are absent today.",
                    "Teacher", teacherId, "/dashboard/teacher-checkin", null,
                    "teacher-attendance-reminder:" + schoolId + ":" + today + ":" + teacherId,
                    Set.of(ExternalDeliveryChannel.PUSH));
        } catch (Exception e) {
            log.error("Failed to send attendance reminder for schoolId={}, localDate={}: {}",
                    schoolId, today, e.getMessage());
        }
    }

    /** now is inside [reminderTime, reminderTime + CATCH_UP_WINDOW). Reminder times configured
     * within CATCH_UP_WINDOW of midnight are not given cross-midnight treatment in v1 — the
     * window simply extends to end-of-day, which is an acceptable, documented v1 limitation
     * for an edge case no real school is expected to configure. */
    static boolean isDueNow(LocalTime reminderTime, LocalTime nowTime) {
        LocalTime windowEnd = reminderTime.plus(CATCH_UP_WINDOW);
        if (windowEnd.isAfter(reminderTime)) {
            return !nowTime.isBefore(reminderTime) && nowTime.isBefore(windowEnd);
        }
        return !nowTime.isBefore(reminderTime);
    }
}
