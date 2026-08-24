package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AdminMarkTeacherAttendanceRequest;
import com.indraacademy.ias_management.dto.TeacherAttendanceResponse;
import com.indraacademy.ias_management.dto.TeacherAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.TeacherAttendanceSessionSummaryDTO;
import com.indraacademy.ias_management.dto.TeacherAttendanceTodaySummaryDTO;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolHoliday;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherAttendance;
import com.indraacademy.ias_management.entity.TeacherLeave;
import com.indraacademy.ias_management.repository.SchoolHolidayRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.TeacherAttendanceRepository;
import com.indraacademy.ias_management.repository.TeacherLeaveRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Owns both halves of teacher/staff attendance: the self-service GPS check-in/check-out and
 * admin-override write paths (unchanged in spirit from before this pass), and — the part this
 * pass rewrote — turning the raw {@link TeacherAttendance} rows into an authoritative account of
 * a period: which days actually counted, and what a teacher's status was on each one.
 *
 * <h2>What "authoritative" means here</h2>
 * A recorded row (from a GPS check-in or an admin override) is always the truth for its own date
 * — nothing here ever second-guesses or overwrites one. The reconciliation this class does is
 * additive: for a day that both (a) the school calendar says was a genuine working day and (b)
 * has fully elapsed with no row at all, it is resolved to ABSENT for the purpose of summaries and
 * the daily view. That resolved entry is never persisted — see {@link TeacherAttendanceResponse#absent}
 * for why a computed fact doesn't need an audit trail the way a human action does.
 *
 * <p>The school calendar itself is {@link School#getWorkingDays()} (a weekly day-of-week pattern)
 * minus any {@link SchoolHoliday} covering that date — both already existed and were already
 * admin-configurable before this pass; the bug was that daily/monthly views never consulted the
 * holiday calendar at all, and derived "which days count" from whichever attendance rows happened
 * to already exist rather than from the calendar itself (see {@code countDistinctWorkingDays}'s
 * old {@code COUNT(DISTINCT date)} — that query is no longer used).
 *
 * <p><b>"Today" is never resolved to ABSENT</b>, no matter how late in the day it's queried — the
 * day isn't over, so a teacher who hasn't checked in yet may still do so. A day only becomes
 * eligible for the ABSENT fill once it is strictly in the past, in the school's own timezone
 * (see {@link com.indraacademy.ias_management.util.SchoolTimeUtil#zoneId}). If a real row for
 * today already exists (a check-in already happened, or
 * an admin already marked it), it's included normally; if not, today is simply omitted from
 * counts until it resolves the following day — the same behavior the UI already showed for
 * "today" before this change, so viewing today live is unaffected.
 */
@Service
public class TeacherAttendanceService {

    private static final Logger log = LoggerFactory.getLogger(TeacherAttendanceService.class);
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    
    public static final String STATUS_ON_TIME = "ON_TIME";
    public static final String STATUS_LATE = "LATE";
    public static final String STATUS_ABSENT = "ABSENT";
    public static final String STATUS_HALF_DAY = "HALF_DAY";
    public static final String STATUS_ON_LEAVE = "ON_LEAVE";
    private static final Set<String> VALID_STATUSES =
            Set.of(STATUS_ON_TIME, STATUS_LATE, STATUS_ABSENT, STATUS_HALF_DAY, STATUS_ON_LEAVE);

    @Autowired private TeacherAttendanceRepository teacherAttendanceRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SchoolHolidayRepository schoolHolidayRepository;
    @Autowired private TeacherLeaveRepository teacherLeaveRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private TeacherAttendanceScheduleService teacherAttendanceScheduleService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private AuditService auditService;
    @Autowired private Clock clock;

    @Transactional
    public TeacherAttendanceResponse checkIn(Double latitude, Double longitude, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String teacherId = securityUtil.getUsername();

        Teacher currentTeacher = teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found"));
        if (currentTeacher.getStatus() == com.indraacademy.ias_management.entity.TeacherStatus.LEFT) {
            throw new IllegalStateException("Your teacher profile is no longer active. Please contact your admin.");
        }

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found"));
        ZoneId zone = SchoolTimeUtil.zoneId(school);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalTime now = LocalTime.now(clock.withZone(zone));

        if (school.getStaffAttendanceTrackingStartDate() != null
                && today.isBefore(school.getStaffAttendanceTrackingStartDate())) {
            throw new IllegalStateException("Staff attendance tracking starts on "
                    + school.getStaffAttendanceTrackingStartDate() + ".");
        }

        // Validate school location is configured
        if (school.getSchoolLatitude() == null || school.getSchoolLongitude() == null) {
            throw new IllegalStateException("School location is not configured. Please contact your admin.");
        }

        // Validate working day — weekly pattern AND the holiday calendar. Previously only the
        // weekly pattern was checked, so a teacher could self-check-in on a declared holiday.
        boolean isHolidayToday = schoolHolidayRepository.existsBySchoolIdAndDateInRange(schoolId, today);
        Map<String, List<com.indraacademy.ias_management.entity.TeacherAttendanceSchedule>> schedules =
                teacherAttendanceScheduleService.schedulesByTeacher(schoolId, today, today);
        String teacherWorkingDays = teacherAttendanceScheduleService
                .workingDaysFor(teacherId, today, school.getWorkingDays(), schedules);
        if (isHolidayToday || !isWorkingDayOfWeek(today, teacherWorkingDays)) {
            throw new IllegalStateException("Today is not a working day in your attendance schedule.");
        }

        // Validate check-in window
        if (school.getCheckinWindowStart() != null && now.isBefore(school.getCheckinWindowStart())) {
            throw new IllegalStateException("Check-in window has not started yet. Opens at " + school.getCheckinWindowStart() + ".");
        }
        if (school.getCheckinWindowEnd() != null && now.isAfter(school.getCheckinWindowEnd())) {
            throw new IllegalStateException("Check-in window has closed. Deadline was " + school.getCheckinWindowEnd() + ".");
        }

        // Check for duplicate
        Optional<TeacherAttendance> existing = teacherAttendanceRepository
                .findByTeacherIdAndDateAndSchoolId(teacherId, today, schoolId);
        if (existing.isPresent()) {
            throw new IllegalStateException("You have already checked in today.");
        }

        // Calculate distance using Haversine
        double distance = calculateDistance(
                latitude, longitude,
                school.getSchoolLatitude(), school.getSchoolLongitude());

        // Validate geofence
        int radius = school.getGeofenceRadius() != null ? school.getGeofenceRadius() : 200;
        if (distance > radius) {
            throw new IllegalStateException(
                    String.format("You are %s away from school. Maximum allowed: %s.", formatDistance(distance), formatDistance(radius)));
        }

        // Determine status
        String status = STATUS_ON_TIME;
        if (school.getSchoolStartTime() != null) {
            int threshold = school.getLateThresholdMinutes() != null ? school.getLateThresholdMinutes() : 5;
            LocalTime lateAfter = school.getSchoolStartTime().plusMinutes(threshold);
            if (now.isAfter(lateAfter)) {
                status = STATUS_LATE;
            }
        }

        // Create record
        TeacherAttendance ta = new TeacherAttendance();
        ta.setTeacherId(teacherId);
        ta.setSchoolId(schoolId);
        ta.setDate(today);
        ta.setCheckInTime(LocalDateTime.now(clock.withZone(zone)));
        ta.setStatus(status);
        ta.setLatitude(latitude);
        ta.setLongitude(longitude);
        ta.setDistanceFromSchool(Math.round(distance * 100.0) / 100.0);
        ta.setMethod("GPS");
        ta.setMarkedByAdmin(false);

        TeacherAttendance saved = teacherAttendanceRepository.save(ta);
        log.info("Teacher {} checked in as {} at school {}", teacherId, status, schoolId);

        auditService.log(
                teacherId,
                securityUtil.getRole(),
                "TEACHER_CHECK_IN",
                "TeacherAttendance",
                String.valueOf(saved.getId()),
                null,
                "status=" + status + ",distance=" + String.format("%.0f", distance) + "m",
                request.getRemoteAddr()
        );

        String teacherName = teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .map(Teacher::getName).orElse(teacherId);
        return TeacherAttendanceResponse.from(saved, teacherName);
    }

    @Transactional
    public TeacherAttendanceResponse checkOut(Double latitude, Double longitude, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String teacherId = securityUtil.getUsername();

        Teacher currentTeacher = teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found"));
        if (currentTeacher.getStatus() == com.indraacademy.ias_management.entity.TeacherStatus.LEFT) {
            throw new IllegalStateException("Your teacher profile is no longer active. Please contact your admin.");
        }

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found"));
        ZoneId zone = SchoolTimeUtil.zoneId(school);
        LocalDate today = LocalDate.now(clock.withZone(zone));

        TeacherAttendance ta = teacherAttendanceRepository
                .findByTeacherIdAndDateAndSchoolId(teacherId, today, schoolId)
                .orElseThrow(() -> new IllegalStateException("No check-in found for today. Please check in first."));

        if (ta.getCheckOutTime() != null) {
            throw new IllegalStateException("You have already checked out today.");
        }

        // Validate geofence for checkout too
        if (school.getSchoolLatitude() != null && school.getSchoolLongitude() != null) {
            double distance = calculateDistance(latitude, longitude,
                    school.getSchoolLatitude(), school.getSchoolLongitude());
            int radius = school.getGeofenceRadius() != null ? school.getGeofenceRadius() : 200;
            if (distance > radius) {
                throw new IllegalStateException(
                        String.format("You are %s away from school. Check out within %s.", formatDistance(distance), formatDistance(radius)));
            }
        }

        ta.setCheckOutTime(LocalDateTime.now(clock.withZone(zone)));
        TeacherAttendance saved = teacherAttendanceRepository.save(ta);
        log.info("Teacher {} checked out at school {}", teacherId, schoolId);

        auditService.log(
                teacherId,
                securityUtil.getRole(),
                "TEACHER_CHECK_OUT",
                "TeacherAttendance",
                String.valueOf(saved.getId()),
                null,
                "checkOutTime=" + saved.getCheckOutTime(),
                request.getRemoteAddr()
        );

        String teacherName = teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .map(Teacher::getName).orElse(teacherId);
        return TeacherAttendanceResponse.from(saved, teacherName);
    }

    @Transactional
    public TeacherAttendanceResponse adminMarkAttendance(AdminMarkTeacherAttendanceRequest req, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String adminUser = securityUtil.getUsername();

        // Verify teacher exists
        Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(req.getTeacherId(), schoolId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + req.getTeacherId()));

        // Validate status
        if (!VALID_STATUSES.contains(req.getStatus().toUpperCase())) {
            throw new IllegalArgumentException("Invalid status: " + req.getStatus());
        }

        School school = schoolRepository.findById(schoolId).orElse(null);
        ZoneId zone = SchoolTimeUtil.zoneId(school);

        // Create or update. An admin override is deliberately allowed on ANY date regardless of
        // the working-day calendar — e.g. correcting a mistake, or recording a teacher who came
        // in on a declared holiday — see this class's Javadoc for why that's different from the
        // self-service checkIn's working-day restriction.
        TeacherAttendance ta = teacherAttendanceRepository
                .findByTeacherIdAndDateAndSchoolId(req.getTeacherId(), req.getDate(), schoolId)
                .orElseGet(() -> {
                    TeacherAttendance newTa = new TeacherAttendance();
                    newTa.setTeacherId(req.getTeacherId());
                    newTa.setSchoolId(schoolId);
                    newTa.setDate(req.getDate());
                    return newTa;
                });

        String oldStatus = ta.getStatus();
        ta.setStatus(req.getStatus().toUpperCase());
        ta.setMethod("MANUAL_ADMIN");
        ta.setMarkedByAdmin(true);

        // Set check-in time from request, or fallback to now for new records
        if (req.getCheckInTime() != null && !req.getCheckInTime().isBlank()) {
            LocalTime parsedIn = LocalTime.parse(req.getCheckInTime());
            ta.setCheckInTime(LocalDateTime.of(req.getDate(), parsedIn));
        } else if (ta.getCheckInTime() == null) {
            ta.setCheckInTime(LocalDateTime.now(clock.withZone(zone)));
        }

        // Set check-out time if provided
        if (req.getCheckOutTime() != null && !req.getCheckOutTime().isBlank()) {
            LocalTime parsedOut = LocalTime.parse(req.getCheckOutTime());
            ta.setCheckOutTime(LocalDateTime.of(req.getDate(), parsedOut));
        }

        TeacherAttendance saved = teacherAttendanceRepository.save(ta);
        log.info("Admin {} marked teacher {} as {} on {} for school {}",
                adminUser, req.getTeacherId(), req.getStatus(), req.getDate(), schoolId);

        auditService.log(
                adminUser,
                securityUtil.getRole(),
                "ADMIN_MARK_TEACHER_ATTENDANCE",
                "TeacherAttendance",
                String.valueOf(saved.getId()),
                oldStatus != null ? "status=" + oldStatus : null,
                "status=" + req.getStatus().toUpperCase(),
                request.getRemoteAddr()
        );

        return TeacherAttendanceResponse.from(saved, teacher.getName());
    }

    /**
     * All teachers' attendance for one day. For a settled (strictly past) working day, any active
     * teacher with no row is resolved to ABSENT — unless an APPROVED TeacherLeave covers the date,
     * in which case it resolves to ON_LEAVE instead — so the list reflects everyone, not just
     * whoever happened to be recorded. A non-working day (weekend or holiday) is never filled,
     * even in the past — nobody is "absent" (or "on leave") from a day the school wasn't open.
     *
     * <p>ABSENT is never synthesized for today — the day isn't over. ON_LEAVE is the one status
     * that IS synthesized for today: an approved leave is already a settled, human-decided fact
     * independent of what time it is, unlike "nobody has checked in yet," which is genuinely still
     * open until the day ends.
     */
    @Transactional(readOnly = true)
    public List<TeacherAttendanceResponse> getAttendanceByDate(LocalDate date) {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId).orElse(null);
        ZoneId zone = SchoolTimeUtil.zoneId(school);
        LocalDate today = LocalDate.now(clock.withZone(zone));

        List<TeacherAttendance> records = teacherAttendanceRepository.findBySchoolIdAndDate(schoolId, date);
        List<Teacher> teachers = teacherRepository.findBySchoolId(schoolId);
        Map<String, String> nameMap = teachers.stream()
                .collect(Collectors.toMap(Teacher::getTeacherId, Teacher::getName, (a, b) -> a));

        List<TeacherAttendanceResponse> result = records.stream()
                .map(ta -> TeacherAttendanceResponse.from(ta, nameMap.getOrDefault(ta.getTeacherId(), ta.getTeacherId())))
                .collect(Collectors.toCollection(ArrayList::new));

        if (school != null && (school.getStaffAttendanceTrackingStartDate() == null
                || !date.isBefore(school.getStaffAttendanceTrackingStartDate()))) {
            List<SchoolHoliday> holidaysOnDate = schoolHolidayRepository.findOverlapping(schoolId, date, date);
            if (!isHolidayDate(date, holidaysOnDate)) {
                boolean pastDay = date.isBefore(today);
                List<TeacherLeave> approvedLeavesOnDate = teacherLeaveRepository.findApprovedOverlapping(schoolId, date, date);
                Map<String, List<com.indraacademy.ias_management.entity.TeacherAttendanceSchedule>> schedules =
                        teacherAttendanceScheduleService.schedulesByTeacher(schoolId, date, date);
                Set<String> recorded = records.stream().map(TeacherAttendance::getTeacherId).collect(Collectors.toSet());
                long syntheticSeq = -1;
                for (Teacher teacher : teachers) {
                    if (recorded.contains(teacher.getTeacherId())) continue;
                    if (teacher.getJoiningDate() != null && date.isBefore(teacher.getJoiningDate())) continue;
                    if (teacher.getLeavingDate() != null && date.isAfter(teacher.getLeavingDate())) continue;
                    String workingDays = teacherAttendanceScheduleService.workingDaysFor(
                            teacher.getTeacherId(), date, school.getWorkingDays(), schedules);
                    if (!isWorkingDayOfWeek(date, workingDays)) continue;
                    if (isCoveredByApprovedLeave(teacher.getTeacherId(), date, approvedLeavesOnDate)) {
                        result.add(TeacherAttendanceResponse.onLeave(
                                syntheticSeq--, teacher.getTeacherId(), teacher.getName(), schoolId, date));
                    } else if (pastDay) {
                        result.add(TeacherAttendanceResponse.absent(
                                syntheticSeq--, teacher.getTeacherId(), teacher.getName(), schoolId, date));
                    }
                }
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public TeacherAttendanceSummaryDTO getMyAttendance(int month, int year) {
        Long schoolId = securityUtil.getSchoolId();
        String teacherId = securityUtil.getUsername();
        return buildSummary(teacherId, schoolId, month, year);
    }

    @Transactional(readOnly = true)
    public TeacherAttendanceSummaryDTO getSummary(int month, int year) {
        Long schoolId = securityUtil.getSchoolId();
        return buildSummary(null, schoolId, month, year);
    }

    @Transactional(readOnly = true)
    public TeacherAttendanceSummaryDTO getTeacherSummary(String teacherId, int month, int year) {
        Long schoolId = securityUtil.getSchoolId();
        teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));
        return buildSummary(teacherId, schoolId, month, year);
    }

    @Transactional(readOnly = true)
    public TeacherAttendanceSessionSummaryDTO getTeacherSessionSummary(String teacherId, String session) {
        Long schoolId = securityUtil.getSchoolId();
        Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found"));

        String[] years = session == null ? new String[0] : session.trim().split("-");
        if (years.length != 2) throw new IllegalArgumentException("Session must use YYYY-YYYY format.");
        int startYear;
        int endYear;
        try {
            startYear = Integer.parseInt(years[0]);
            endYear = Integer.parseInt(years[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Session must use YYYY-YYYY format.");
        }
        if (endYear != startYear + 1) {
            throw new IllegalArgumentException("Academic session must span consecutive years.");
        }

        int startMonth = school.getAcademicYearStartMonth();
        YearMonth cursor = YearMonth.of(startYear, startMonth);
        List<TeacherAttendanceSessionSummaryDTO.MonthlySummary> months = new ArrayList<>();
        int working = 0, present = 0, late = 0, absent = 0, halfDay = 0, onLeave = 0;
        for (int i = 0; i < 12; i++) {
            TeacherAttendanceSummaryDTO summary = buildSummary(
                    teacherId, schoolId, cursor.getMonthValue(), cursor.getYear());
            months.add(new TeacherAttendanceSessionSummaryDTO.MonthlySummary(
                    cursor.getMonthValue(), cursor.getYear(), summary));
            working += summary.getTotalWorkingDays();
            present += summary.getPresentDays();
            late += summary.getLateDays();
            absent += summary.getAbsentDays();
            halfDay += summary.getHalfDayDays();
            onLeave += summary.getOnLeaveDays();
            cursor = cursor.plusMonths(1);
        }

        TeacherAttendanceSessionSummaryDTO result = new TeacherAttendanceSessionSummaryDTO();
        result.setSession(session);
        result.setTeacherId(teacherId);
        result.setTeacherName(teacher.getName());
        result.setTotalWorkingDays(working);
        result.setPresentDays(present);
        result.setLateDays(late);
        result.setAbsentDays(absent);
        result.setHalfDayDays(halfDay);
        result.setOnLeaveDays(onLeave);
        result.setAttendancePercentage(attendancePercentage(working, present, halfDay, onLeave));
        result.setOnTimePercentage(present > 0
                ? Math.round(((present - late) * 100.0 / present) * 10.0) / 10.0 : 0.0);
        result.setMonths(months);
        return result;
    }

    /**
     * Whole-school status counts for today only, for the admin dashboard's compact staff
     * attendance widget. Built on top of {@link #getAttendanceByDate}, the same authoritative,
     * calendar/holiday/leave-aware call the full Staff Attendance page uses — no counts are
     * re-derived independently here.
     */
    @Transactional(readOnly = true)
    public TeacherAttendanceTodaySummaryDTO getTodaySummary() {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId).orElse(null);
        ZoneId zone = SchoolTimeUtil.zoneId(school);
        LocalDate today = LocalDate.now(clock.withZone(zone));

        List<TeacherAttendanceResponse> records = getAttendanceByDate(today);

        TeacherAttendanceTodaySummaryDTO dto = new TeacherAttendanceTodaySummaryDTO();
        dto.setDate(today);
        dto.setPresentCount((int) records.stream().filter(r -> STATUS_ON_TIME.equals(r.getStatus())).count());
        dto.setLateCount((int) records.stream().filter(r -> STATUS_LATE.equals(r.getStatus())).count());
        dto.setAbsentCount((int) records.stream().filter(r -> STATUS_ABSENT.equals(r.getStatus())).count());
        dto.setHalfDayCount((int) records.stream().filter(r -> STATUS_HALF_DAY.equals(r.getStatus())).count());
        dto.setOnLeaveCount((int) records.stream().filter(r -> STATUS_ON_LEAVE.equals(r.getStatus())).count());
        return dto;
    }

    // ── Private helpers ──

    /**
     * Shared by getMyAttendance (teacherId set) and getSummary (teacherId null, whole school).
     *
     * <p>{@code totalWorkingDays} keeps its pre-existing meaning for each caller: for a single
     * teacher it is that teacher's own count of settled working days on or after their joining
     * date, which by construction equals presentDays+absentDays+halfDayDays+onLeaveDays exactly
     * (a useful invariant — see TeacherAttendanceServiceTest). For the whole-school summary it
     * remains a single school-wide "days open" figure, same semantic as before this pass, just
     * now sourced from the calendar instead of from whichever rows happened to exist.
     */
    private TeacherAttendanceSummaryDTO buildSummary(String teacherIdFilter, Long schoolId, int month, int year) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found"));
        ZoneId zone = SchoolTimeUtil.zoneId(school);
        LocalDate today = LocalDate.now(clock.withZone(zone));

        YearMonth ym = YearMonth.of(year, month);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        LocalDate trackingStart = school.getStaffAttendanceTrackingStartDate();
        LocalDate calculationStart = trackingStart != null && trackingStart.isAfter(monthStart)
                ? trackingStart : monthStart;

        // Settled = fully elapsed days only. Today is handled separately below: a real row
        // included normally, an ABSENT never synthesized (the day isn't over) but an ON_LEAVE
        // still synthesized when covered by approved leave (see getAttendanceByDate's Javadoc
        // for why that one status is different).
        LocalDate settledEnd = monthEnd.isBefore(today) ? monthEnd : today.minusDays(1);
        boolean hasSettledDays = !settledEnd.isBefore(calculationStart);

        // Fetched for the WHOLE month (not just the settled portion) in one shot each, since
        // today's ON_LEAVE check needs them too — both are cheap, indexed, single queries either way.
        List<SchoolHoliday> holidays = schoolHolidayRepository.findOverlapping(schoolId, monthStart, monthEnd);
        List<TeacherLeave> approvedLeaves = teacherLeaveRepository.findApprovedOverlapping(schoolId, monthStart, monthEnd);
        List<LocalDate> settledWorkingDays = hasSettledDays
                ? computeWorkingDays(calculationStart, settledEnd, school.getWorkingDays(), holidays)
                : List.of();
        Map<String, List<com.indraacademy.ias_management.entity.TeacherAttendanceSchedule>> schedules =
                teacherAttendanceScheduleService.schedulesByTeacher(schoolId, monthStart, monthEnd);

        List<Teacher> teachers;
        if (teacherIdFilter != null) {
            Optional<Teacher> t = teacherRepository.findByTeacherIdAndSchoolId(teacherIdFilter, schoolId);
            if (t.isEmpty()) {
                TeacherAttendanceSummaryDTO empty = new TeacherAttendanceSummaryDTO();
                empty.setTrackingStartDate(trackingStart);
                empty.setRecords(List.of());
                return empty;
            }
            teachers = List.of(t.get());
        } else {
            teachers = teacherRepository.findBySchoolId(schoolId);
        }

        List<TeacherAttendance> allRows = teacherIdFilter != null
                ? teacherAttendanceRepository.findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
                        teacherIdFilter, schoolId, monthStart, monthEnd)
                : teacherAttendanceRepository.findBySchoolIdAndDateBetweenOrderByDateAsc(schoolId, monthStart, monthEnd);

        Map<String, Map<LocalDate, TeacherAttendance>> rowsByTeacherThenDate = allRows.stream()
                .collect(Collectors.groupingBy(TeacherAttendance::getTeacherId,
                        Collectors.toMap(TeacherAttendance::getDate, r -> r, (a, b) -> a)));

        List<TeacherAttendanceResponse> merged = new ArrayList<>();
        long syntheticSeq = -1;
        boolean todayInRange = !today.isBefore(monthStart) && !today.isAfter(monthEnd)
                && (trackingStart == null || !today.isBefore(trackingStart));
        int totalWorkingDaysForFilter = 0;

        for (Teacher teacher : teachers) {
            String tid = teacher.getTeacherId();
            String tname = teacher.getName();
            LocalDate joinDate = teacher.getJoiningDate();
            LocalDate leavingDate = teacher.getLeavingDate();
            Map<LocalDate, TeacherAttendance> rowsForTeacher = rowsByTeacherThenDate.getOrDefault(tid, Map.of());

            for (LocalDate day = calculationStart; hasSettledDays && !day.isAfter(settledEnd); day = day.plusDays(1)) {
                if (joinDate != null && day.isBefore(joinDate)) continue;
                if (leavingDate != null && day.isAfter(leavingDate)) continue;
                String workingDays = teacherAttendanceScheduleService.workingDaysFor(
                        tid, day, school.getWorkingDays(), schedules);
                if (!isWorkingDay(day, workingDays, holidays)) continue;
                if (teacherIdFilter != null) totalWorkingDaysForFilter++;
                TeacherAttendance existing = rowsForTeacher.get(day);
                if (existing != null) {
                    merged.add(TeacherAttendanceResponse.from(existing, tname));
                } else if (isCoveredByApprovedLeave(tid, day, approvedLeaves)) {
                    merged.add(TeacherAttendanceResponse.onLeave(syntheticSeq--, tid, tname, schoolId, day));
                } else {
                    merged.add(TeacherAttendanceResponse.absent(syntheticSeq--, tid, tname, schoolId, day));
                }
            }

            String todayWorkingDays = teacherAttendanceScheduleService.workingDaysFor(
                    tid, today, school.getWorkingDays(), schedules);
            if (todayInRange && (joinDate == null || !today.isBefore(joinDate))
                    && (leavingDate == null || !today.isAfter(leavingDate))
                    && isWorkingDay(today, todayWorkingDays, holidays)) {
                TeacherAttendance todayRow = rowsForTeacher.get(today);
                if (todayRow != null) {
                    merged.add(TeacherAttendanceResponse.from(todayRow, tname));
                    if (teacherIdFilter != null) totalWorkingDaysForFilter++;
                } else if (isCoveredByApprovedLeave(tid, today, approvedLeaves)) {
                    merged.add(TeacherAttendanceResponse.onLeave(syntheticSeq--, tid, tname, schoolId, today));
                    if (teacherIdFilter != null) totalWorkingDaysForFilter++;
                }
            }
        }

        merged.sort(Comparator.comparing(TeacherAttendanceResponse::getDate)
                .thenComparing(TeacherAttendanceResponse::getTeacherId));

        int present = (int) merged.stream().filter(r -> STATUS_ON_TIME.equals(r.getStatus()) || STATUS_LATE.equals(r.getStatus())).count();
        int late = (int) merged.stream().filter(r -> STATUS_LATE.equals(r.getStatus())).count();
        int absent = (int) merged.stream().filter(r -> STATUS_ABSENT.equals(r.getStatus())).count();
        int halfDay = (int) merged.stream().filter(r -> STATUS_HALF_DAY.equals(r.getStatus())).count();
        int onLeave = (int) merged.stream().filter(r -> STATUS_ON_LEAVE.equals(r.getStatus())).count();

        TeacherAttendanceSummaryDTO dto = new TeacherAttendanceSummaryDTO();
        dto.setTotalWorkingDays(teacherIdFilter != null ? totalWorkingDaysForFilter : settledWorkingDays.size());
        dto.setPresentDays(present);
        dto.setLateDays(late);
        dto.setAbsentDays(absent);
        dto.setHalfDayDays(halfDay);
        dto.setOnLeaveDays(onLeave);
        dto.setOnTimePercentage(present > 0 ? Math.round(((present - late) * 100.0 / present) * 10.0) / 10.0 : 0);
        dto.setAttendancePercentage(teacherIdFilter != null
                ? attendancePercentage(totalWorkingDaysForFilter, present, halfDay, onLeave) : 0.0);
        dto.setTrackingStartDate(trackingStart);
        dto.setRecords(merged);
        return dto;
    }

    private double attendancePercentage(int workingDays, int presentDays, int halfDayDays, int onLeaveDays) {
        int eligibleDays = Math.max(0, workingDays - onLeaveDays);
        if (eligibleDays == 0) return 0.0;
        double attendedDays = presentDays + (halfDayDays * 0.5);
        return Math.round(attendedDays * 1000.0 / eligibleDays) / 10.0;
    }

    private List<LocalDate> computeWorkingDays(LocalDate start, LocalDate end, String workingDaysPattern, List<SchoolHoliday> holidays) {
        List<LocalDate> result = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (isWorkingDay(d, workingDaysPattern, holidays)) result.add(d);
        }
        return result;
    }

    /** A working day is a configured weekday that is not covered by any holiday. */
    private boolean isWorkingDay(LocalDate date, String workingDaysPattern, List<SchoolHoliday> holidays) {
        return isWorkingDayOfWeek(date, workingDaysPattern) && !isHolidayDate(date, holidays);
    }

    private boolean isWorkingDayOfWeek(LocalDate date, String workingDays) {
        if (workingDays == null || workingDays.isBlank()) return false;
        String dayName = date.getDayOfWeek().name();
        return Arrays.stream(workingDays.split(","))
                .map(String::trim)
                .anyMatch(dayName::equalsIgnoreCase);
    }

    /**
     * Whether an APPROVED leave for this specific teacher covers this date. PENDING and REJECTED
     * leaves never reach this check — {@code findApprovedOverlapping} only ever returns APPROVED
     * rows, so a request still awaiting a decision correctly continues to resolve as ABSENT (or,
     * once the day arrives and nobody's checked in, simply omitted) until an admin actually
     * approves it.
     */
    private boolean isCoveredByApprovedLeave(String teacherId, LocalDate date, List<TeacherLeave> approvedLeaves) {
        for (TeacherLeave leave : approvedLeaves) {
            if (!teacherId.equals(leave.getTeacherId())) continue;
            if (!date.isBefore(leave.getStartDate()) && !date.isAfter(leave.getEndDate())) return true;
        }
        return false;
    }

    private boolean isHolidayDate(LocalDate date, List<SchoolHoliday> holidays) {
        for (SchoolHoliday h : holidays) {
            if (!date.isBefore(h.getStartDate()) && !date.isAfter(h.getEndDate())) return true;
        }
        return false;
    }

    private String formatDistance(double meters) {
        if (meters >= 1000) {
            return String.format("%.1f km", meters / 1000);
        }
        return String.format("%.0f meters", meters);
    }

    /**
     * Haversine distance between two GPS coordinates, in meters.
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }
}
