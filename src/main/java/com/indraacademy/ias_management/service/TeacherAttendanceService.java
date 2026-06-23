package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AdminMarkTeacherAttendanceRequest;
import com.indraacademy.ias_management.dto.TeacherAttendanceResponse;
import com.indraacademy.ias_management.dto.TeacherAttendanceSummaryDTO;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherAttendance;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.TeacherAttendanceRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TeacherAttendanceService {

    private static final Logger log = LoggerFactory.getLogger(TeacherAttendanceService.class);
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    @Autowired private TeacherAttendanceRepository teacherAttendanceRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private AuditService auditService;

    @Transactional
    public TeacherAttendanceResponse checkIn(Double latitude, Double longitude, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String teacherId = securityUtil.getUsername();
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found"));

        // Validate school location is configured
        if (school.getSchoolLatitude() == null || school.getSchoolLongitude() == null) {
            throw new IllegalStateException("School location is not configured. Please contact your admin.");
        }

        // Validate working day
        if (!isWorkingDay(today, school.getWorkingDays())) {
            throw new IllegalStateException("Today is not a working day.");
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
        String status = "ON_TIME";
        if (school.getSchoolStartTime() != null) {
            int threshold = school.getLateThresholdMinutes() != null ? school.getLateThresholdMinutes() : 5;
            LocalTime lateAfter = school.getSchoolStartTime().plusMinutes(threshold);
            if (now.isAfter(lateAfter)) {
                status = "LATE";
            }
        }

        // Create record
        TeacherAttendance ta = new TeacherAttendance();
        ta.setTeacherId(teacherId);
        ta.setSchoolId(schoolId);
        ta.setDate(today);
        ta.setCheckInTime(LocalDateTime.now());
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
        LocalDate today = LocalDate.now();

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found"));

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

        ta.setCheckOutTime(LocalDateTime.now());
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
        Set<String> validStatuses = Set.of("ON_TIME", "LATE", "ABSENT", "HALF_DAY", "ON_LEAVE");
        if (!validStatuses.contains(req.getStatus().toUpperCase())) {
            throw new IllegalArgumentException("Invalid status: " + req.getStatus());
        }

        // Create or update
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
            ta.setCheckInTime(LocalDateTime.now());
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

    public List<TeacherAttendanceResponse> getAttendanceByDate(LocalDate date) {
        Long schoolId = securityUtil.getSchoolId();
        List<TeacherAttendance> records = teacherAttendanceRepository.findBySchoolIdAndDate(schoolId, date);

        // Build teacher name map
        Map<String, String> nameMap = teacherRepository.findBySchoolId(schoolId).stream()
                .collect(Collectors.toMap(Teacher::getTeacherId, Teacher::getName, (a, b) -> a));

        return records.stream()
                .map(ta -> TeacherAttendanceResponse.from(ta, nameMap.getOrDefault(ta.getTeacherId(), ta.getTeacherId())))
                .collect(Collectors.toList());
    }

    public TeacherAttendanceSummaryDTO getMyAttendance(int month, int year) {
        Long schoolId = securityUtil.getSchoolId();
        String teacherId = securityUtil.getUsername();
        return buildSummary(teacherId, schoolId, month, year);
    }

    public TeacherAttendanceSummaryDTO getSummary(int month, int year) {
        Long schoolId = securityUtil.getSchoolId();
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<TeacherAttendance> records = teacherAttendanceRepository
                .findBySchoolIdAndDateBetweenOrderByDateAsc(schoolId, start, end);

        Map<String, String> nameMap = teacherRepository.findBySchoolId(schoolId).stream()
                .collect(Collectors.toMap(Teacher::getTeacherId, Teacher::getName, (a, b) -> a));

        LocalDate effectiveEnd = end.isAfter(LocalDate.now()) ? LocalDate.now() : end;
        int workingDays = teacherAttendanceRepository.countDistinctWorkingDays(schoolId, start, effectiveEnd);

        List<TeacherAttendanceResponse> responseList = records.stream()
                .map(ta -> TeacherAttendanceResponse.from(ta, nameMap.getOrDefault(ta.getTeacherId(), ta.getTeacherId())))
                .collect(Collectors.toList());

        int present = (int) records.stream().filter(r -> "ON_TIME".equals(r.getStatus()) || "LATE".equals(r.getStatus())).count();
        int late = (int) records.stream().filter(r -> "LATE".equals(r.getStatus())).count();
        int absent = (int) records.stream().filter(r -> "ABSENT".equals(r.getStatus())).count();
        int halfDay = (int) records.stream().filter(r -> "HALF_DAY".equals(r.getStatus())).count();
        int onLeave = (int) records.stream().filter(r -> "ON_LEAVE".equals(r.getStatus())).count();

        TeacherAttendanceSummaryDTO dto = new TeacherAttendanceSummaryDTO();
        dto.setTotalWorkingDays(workingDays);
        dto.setPresentDays(present);
        dto.setLateDays(late);
        dto.setAbsentDays(absent);
        dto.setHalfDayDays(halfDay);
        dto.setOnLeaveDays(onLeave);
        dto.setOnTimePercentage(present > 0 ? Math.round(((present - late) * 100.0 / present) * 10.0) / 10.0 : 0);
        dto.setRecords(responseList);
        return dto;
    }

    // ── Private helpers ──

    private TeacherAttendanceSummaryDTO buildSummary(String teacherId, Long schoolId, int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<TeacherAttendance> records = teacherAttendanceRepository
                .findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(teacherId, schoolId, start, end);

        String teacherName = teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .map(Teacher::getName).orElse(teacherId);

        LocalDate effectiveEnd = end.isAfter(LocalDate.now()) ? LocalDate.now() : end;
        int workingDays = teacherAttendanceRepository.countDistinctWorkingDays(schoolId, start, effectiveEnd);

        List<TeacherAttendanceResponse> responseList = records.stream()
                .map(ta -> TeacherAttendanceResponse.from(ta, teacherName))
                .collect(Collectors.toList());

        int present = (int) records.stream().filter(r -> "ON_TIME".equals(r.getStatus()) || "LATE".equals(r.getStatus())).count();
        int late = (int) records.stream().filter(r -> "LATE".equals(r.getStatus())).count();
        int absent = (int) records.stream().filter(r -> "ABSENT".equals(r.getStatus())).count();
        int halfDay = (int) records.stream().filter(r -> "HALF_DAY".equals(r.getStatus())).count();
        int onLeave = (int) records.stream().filter(r -> "ON_LEAVE".equals(r.getStatus())).count();

        TeacherAttendanceSummaryDTO dto = new TeacherAttendanceSummaryDTO();
        dto.setTotalWorkingDays(workingDays);
        dto.setPresentDays(present);
        dto.setLateDays(late);
        dto.setAbsentDays(absent);
        dto.setHalfDayDays(halfDay);
        dto.setOnLeaveDays(onLeave);
        dto.setOnTimePercentage(present > 0 ? Math.round(((present - late) * 100.0 / present) * 10.0) / 10.0 : 0);
        dto.setRecords(responseList);
        return dto;
    }

    private boolean isWorkingDay(LocalDate date, String workingDays) {
        if (workingDays == null || workingDays.isBlank()) return true;
        String dayName = date.getDayOfWeek().name(); // e.g. MONDAY
        return workingDays.toUpperCase().contains(dayName);
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
