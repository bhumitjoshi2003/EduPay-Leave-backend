package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ConsecutiveAbsenceDTO;
import com.indraacademy.ias_management.dto.DailyAttendanceDTO;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);
    private static final Set<String> VALID_STATUSES =
            Set.of("ABSENT", "PRESENT", "HALF_DAY", "LATE", "EXCUSED");

    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AcademicSessionService academicSessionService;
    @Autowired private StudentTemporalMembershipResolver temporalMembershipResolver;
    @Autowired private StudentEnrollmentRepository studentEnrollmentRepository;
    @Autowired private AcademicSessionRepository academicSessionRepository;

    @Transactional
    public void saveAttendance(List<Attendance> attendanceList, HttpServletRequest request) {
        saveAttendance(attendanceList, null, request);
    }

    @Transactional
    public void saveAttendance(List<Attendance> attendanceList, Long sectionId, HttpServletRequest request) {

        if (attendanceList == null || attendanceList.isEmpty()) {
            log.warn("Attempted to save empty or null attendance list.");
            return;
        }

        LocalDate absentDate = attendanceList.getFirst().getDate();
        String className = attendanceList.getFirst().getClassName();

        if (absentDate == null || className == null || className.isBlank()
                || attendanceList.stream().anyMatch(a -> !Objects.equals(absentDate, a.getDate())
                || !Objects.equals(className, a.getClassName()))) {
            throw new IllegalArgumentException("All attendance rows must use the same date and class.");
        }
        if (attendanceList.stream().noneMatch(a -> "X".equals(a.getStudentId()))) {
            throw new IllegalArgumentException("Attendance submission marker is missing.");
        }

        log.info("Saving attendance for date: {} and class: {}", absentDate, className);

        try {
            Long schoolId = securityUtil.getSchoolId();

            School school = schoolRepository.findById(schoolId)
                    .orElseThrow(() -> new NoSuchElementException("School not found"));
            if (!isConfiguredWorkingDay(absentDate, school.getWorkingDays())) {
                throw new IllegalArgumentException("Attendance cannot be marked on a configured non-working day.");
            }

            List<Student> classStudents = studentRepository.findByClassNameAndSchoolId(className, schoolId);
            Map<String, Student> studentsById = classStudents.stream()
                    .collect(Collectors.toMap(Student::getStudentId, s -> s, (a, b) -> a));
            for (Attendance a : attendanceList) {
                if ("X".equals(a.getStudentId())) continue;
                Student student = studentsById.get(a.getStudentId());
                if (student == null) {
                    throw new IllegalArgumentException("Student " + a.getStudentId() + " does not belong to class " + className + ".");
                }
                if (sectionId != null && !Objects.equals(sectionId, student.getSectionId())) {
                    throw new IllegalArgumentException("Student " + a.getStudentId() + " does not belong to the selected section.");
                }
                a.setSectionId(student.getSectionId());
            }

            // Set schoolId and markedBy on each attendance record before saving
            String markedBy = securityUtil.getUsername();
            for (Attendance a : attendanceList) {
                a.setSchoolId(schoolId);
                if (a.getMarkedBy() == null) {
                    a.setMarkedBy(markedBy);
                }
                String status = a.getStatus() == null ? "ABSENT" : a.getStatus().trim().toUpperCase(Locale.ROOT);
                if (!VALID_STATUSES.contains(status)) {
                    throw new IllegalArgumentException("Unsupported attendance status: " + a.getStatus());
                }
                a.setStatus(status);
            }

            // Dual-write: resolve className → classId
            Long classId = schoolClassRepository.findBySchoolIdAndName(schoolId, className)
                    .map(sc -> sc.getId()).orElse(null);
            if (classId != null) {
                for (Attendance a : attendanceList) { a.setClassId(classId); }
            }

            // Capture old state before deletion
            List<Attendance> oldRecords =
                    attendanceRepository.findByDateAndClassNameAndSchoolId(absentDate, className, schoolId);

            String oldValue = objectMapper.writeValueAsString(oldRecords);

            if (sectionId != null) {
                Set<String> sectionStudentIds = classStudents.stream()
                        .filter(s -> Objects.equals(sectionId, s.getSectionId()))
                        .map(Student::getStudentId)
                        .collect(Collectors.toSet());
                List<Attendance> toDelete = oldRecords.stream()
                        .filter(a -> sectionStudentIds.contains(a.getStudentId())
                                || ("X".equals(a.getStudentId()) && Objects.equals(sectionId, a.getSectionId())))
                        .toList();
                attendanceRepository.deleteAll(toDelete);
                attendanceList.forEach(a -> {
                    if ("X".equals(a.getStudentId())) a.setSectionId(sectionId);
                });
                attendanceRepository.saveAll(attendanceList);
            } else {
                attendanceRepository.deleteByDateAndClassNameAndSchoolId(absentDate, className, schoolId);
                attendanceRepository.saveAll(attendanceList);
            }

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "SAVE_ATTENDANCE",
                    "Attendance",
                    absentDate + "_" + className,
                    oldValue,
                    objectMapper.writeValueAsString(attendanceList),
                    request.getRemoteAddr()
            );

            log.info("Successfully saved attendance for date: {} and class: {}", absentDate, className);

        } catch (DataAccessException e) {
            log.error("Error saving attendance for date {} and class {}", absentDate, className, e);
            throw new RuntimeException("Could not save attendance", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public List<Attendance> getAttendanceByDateAndClass(LocalDate absentDate, String className) {
        return getAttendanceByDateAndClass(absentDate, className, null);
    }

    @Transactional(readOnly = true)
    public List<Attendance> getAttendanceByDateAndClass(LocalDate absentDate, String className, Long sectionId) {
        if (absentDate == null || className == null || className.trim().isEmpty()) {
            log.warn("Attempted to fetch attendance with null date or empty class name.");
            return Collections.emptyList();
        }
        log.info("Fetching attendance for date: {} and class: {}", absentDate, className);
        try {
            Long schoolId = securityUtil.getSchoolId();
            List<Attendance> attendanceList = attendanceRepository.findByDateAndClassNameAndSchoolId(absentDate, className, schoolId);
            if (sectionId != null) {
                // Historically-accurate readback (E6C): each row already snapshots the section it
                // was marked under (see saveAttendance's dual-write) — use that snapshot directly
                // rather than the student's CURRENT live section, which may have since changed.
                // Only fall back to live Student data for legacy rows saved before sectionId was
                // captured (row.getSectionId() == null).
                Set<String> legacySectionStudentIds = studentRepository
                        .findByClassNameAndSectionIdAndSchoolId(className, sectionId, schoolId).stream()
                        .map(Student::getStudentId).collect(Collectors.toSet());
                attendanceList = attendanceList.stream()
                        .filter(a -> a.getSectionId() != null
                                ? Objects.equals(sectionId, a.getSectionId())
                                : (legacySectionStudentIds.contains(a.getStudentId()) || "X".equals(a.getStudentId())))
                        .collect(Collectors.toList());
            }
            log.info("Found {} attendance records for date: {} and class: {}", attendanceList.size(), absentDate, className);
            return attendanceList;
        } catch (DataAccessException e) {
            log.error("Data access error fetching attendance for date {} and class {}", absentDate, className, e);
            throw new RuntimeException("Could not retrieve attendance due to data access issue", e);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getAttendanceCounts(String studentId, int year, int month) {
        Map<String, Long> counts = new HashMap<>();
        counts.put("studentAbsent", 0L);
        counts.put("totalAbsent", 0L);

        if (studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to get attendance counts with null or empty student ID.");
            return counts;
        }
        log.info("Calculating attendance counts for student ID: {} for year: {} month: {}", studentId, year, month);

        Long schoolId = securityUtil.getSchoolId();
        Student student;
        try {
            Optional<Student> studentOptional = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId);
            if (studentOptional.isEmpty()) {
                log.warn("Student not found with ID: {}", studentId);
                return counts;
            }
            student = studentOptional.get();
        } catch (DataAccessException e) {
            log.error("Data access error fetching student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve student info for counts", e);
        }

        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        LocalDate effectiveStart = effectiveStart(student, monthStart);
        LocalDate effectiveEnd = effectiveEnd(student, monthEnd);

        long studentAbsentCount;
        long totalWorkingDays;
        try {
            // Enrollment-aware resolution (see resolveHistoricalAttendance): correctly partitions
            // the denominator across any mid-month class/section change, excludes authoritative
            // enrollment gaps, and falls back to the legacy attendance-row bridge when no
            // enrollment coverage exists — replacing the old whole-month COUNT queries plus
            // separate before-join/after-leave subtraction, which effectiveStart/effectiveEnd
            // above already achieve by construction.
            HistoricalAttendanceResult result = resolveHistoricalAttendance(
                    schoolId, studentId, effectiveStart, effectiveEnd, student.getClassName());
            totalWorkingDays = result.workingDays().size();
            studentAbsentCount = result.countableRows().stream().filter(this::isFullAbsence).count();
        } catch (DataAccessException e) {
            log.error("Data access error calculating absence counts for student ID: {}", studentId, e);
            throw new RuntimeException("Could not calculate attendance counts", e);
        }

        counts.put("studentAbsent", studentAbsentCount);
        counts.put("totalAbsent", totalWorkingDays);
        log.info("Finished calculating counts for student ID: {}. Student Absent: {}, Total Working Days: {}",
                studentId, studentAbsentCount, totalWorkingDays);

        return counts;
    }


    @Transactional(readOnly = true)
    public LocalDate getStudentJoinDate(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to get student join date with null or empty ID.");
            return null;
        }
        log.info("Fetching join date for student ID: {}", studentId);
        try {
            return studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId())
                    .map(Student::getJoiningDate)
                    .orElseGet(() -> {
                        log.warn("Student not found with ID: {}", studentId);
                        return null;
                    });
        } catch (DataAccessException e) {
            log.error("Data access error fetching join date for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve student join date", e);
        }
    }

    @Transactional(readOnly = true)
    public long getTotalUnappliedLeaveCount(String studentId, String session) {
        if (studentId == null || studentId.trim().isEmpty() || session == null || session.trim().isEmpty()) {
            log.warn("Attempted to get unapplied leave count with null/empty student ID or session.");
            return 0L;
        }
        log.info("Fetching total unapplied leave count for student ID: {} and session: {}", studentId, session);

        try {
            Long schoolId = securityUtil.getSchoolId();
            Optional<AcademicSession> academicSession = academicSessionService.getSessionByLabel(schoolId, session);
            if (academicSession.isEmpty()) {
                log.warn("No AcademicSession found for schoolId={} label='{}' — returning 0 unapplied-leave count.", schoolId, session);
                return 0L;
            }
            long count = attendanceRepository.countUnappliedLeavesForAcademicYear(
                    studentId, schoolId, academicSession.get().getStartDate(), academicSession.get().getEndDate());
            log.info("Total unapplied leave count for student ID: {} is {}", studentId, count);
            return count;
        } catch (DataAccessException e) {
            log.error("Data access error fetching unapplied leave count for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve unapplied leave count", e);
        }
    }

    @Transactional
    public void updateChargePaidAfterPayment(String studentId,
                                             String session,
                                             HttpServletRequest request) {
        updateChargePaidAfterPayment(studentId, session, securityUtil.getSchoolId(), request);
    }

    /** Required Correctness Fix — Fix B: explicit trusted {@code schoolId}, for the payment
     * settlement path (PaymentSettlementService.settle, both client-verify AND webhook
     * recovery) where ambient SecurityUtil/SchoolContext is never populated for a genuine
     * /api/webhooks/* request. settle() already validates and holds this schoolId itself
     * (against the trusted PaymentOrder) before calling here — never re-derive it ambiently,
     * the same principle already applied to StudentFeesService.markFeesAsPaid. */
    @Transactional
    public void updateChargePaidAfterPayment(String studentId, String session, Long schoolId) {
        updateChargePaidAfterPayment(studentId, session, schoolId, null);
    }

    private void updateChargePaidAfterPayment(String studentId,
                                             String session,
                                             Long schoolId,
                                             HttpServletRequest request) {

        if (studentId == null || studentId.trim().isEmpty()
                || session == null || session.trim().isEmpty()) {
            log.warn("Invalid input for updateChargePaidAfterPayment.");
            return;
        }

        try {
            Optional<AcademicSession> academicSession = academicSessionService.getSessionByLabel(schoolId, session);
            if (academicSession.isEmpty()) {
                log.warn("No AcademicSession found for schoolId={} label='{}' — skipping chargePaid update for student {}.",
                        schoolId, session, studentId);
                return;
            }

            attendanceRepository.updateChargePaidForSession(
                    studentId, schoolId, academicSession.get().getStartDate(), academicSession.get().getEndDate());

            String ipAddress = (request != null) ? request.getRemoteAddr() : "SYSTEM";

            String username = securityUtil.getUsername();
            String role = securityUtil.getRole();

            if (username == null) username = "SYSTEM";
            if (role == null) role = "SYSTEM";

            auditService.log(
                    username,
                    role,
                    "UPDATE_ATTENDANCE_CHARGE_PAID",
                    "Attendance",
                    studentId + "_" + session,
                    null,
                    "ChargePaid updated for session",
                    ipAddress
            );

            log.info("ChargePaid updated for student ID: {}", studentId);

        } catch (Exception e) {
            log.error("Error updating chargePaid for student ID: {}", studentId, e);
            throw new RuntimeException("Could not update chargePaid", e);
        }
    }

    @Transactional
    public void deleteAttendanceByDateAndClass(LocalDate date,
                                               String className,
                                               HttpServletRequest request) {
        deleteAttendanceByDateAndClass(date, className, null, request);
    }

    @Transactional
    public void deleteAttendanceByDateAndClass(LocalDate date,
                                               String className,
                                               Long sectionId,
                                               HttpServletRequest request) {

        if (date == null || className == null || className.trim().isEmpty()) {
            log.warn("Invalid input for deleteAttendanceByDateAndClass.");
            return;
        }

        try {
            Long schoolId = securityUtil.getSchoolId();
            List<Attendance> oldRecords =
                    attendanceRepository.findByDateAndClassNameAndSchoolId(date, className, schoolId);

            List<Attendance> recordsToDelete = oldRecords;
            if (sectionId != null) {
                Set<String> sectionStudentIds = studentRepository
                        .findByClassNameAndSectionIdAndSchoolId(className, sectionId, schoolId).stream()
                        .map(Student::getStudentId).collect(Collectors.toSet());
                recordsToDelete = oldRecords.stream()
                        .filter(a -> sectionStudentIds.contains(a.getStudentId())
                                || ("X".equals(a.getStudentId()) && Objects.equals(sectionId, a.getSectionId())))
                        .toList();
            }

            String oldValue = objectMapper.writeValueAsString(recordsToDelete);

            if (sectionId == null) {
                attendanceRepository.deleteByDateAndClassNameAndSchoolId(date, className, schoolId);
            } else {
                attendanceRepository.deleteAll(recordsToDelete);
            }

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "DELETE_ATTENDANCE",
                    "Attendance",
                    date + "_" + className,
                    oldValue,
                    null,
                    request.getRemoteAddr()
            );

            log.info("Attendance deleted for date: {} and class: {}", date, className);

        } catch (DataAccessException e) {
            log.error("Error deleting attendance for date {} and class {}", date, className, e);
            throw new RuntimeException("Could not delete attendance", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }


    @Transactional(readOnly = true)
    public List<Attendance> getAttendanceByStudentClassMonthAndYear(String studentId, String className, int year, int month) {
        log.info("Fetching monthly attendance for Student: {} in Class: {} for {}-{}", studentId, className, year, month);
        try {
            LocalDate startDate = LocalDate.of(year, month, 1);
            LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());

            return attendanceRepository.findByStudentIdAndClassNameAndDateRange(studentId, securityUtil.getSchoolId(), className, startDate, endDate);
        } catch (Exception e) {
            log.error("Error fetching attendance for student: {} in class: {}", studentId, className, e);
            throw new RuntimeException("Could not retrieve attendance records");
        }
    }

    // ─── Summary endpoints ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AttendanceSummaryDTO getStudentSummary(String studentId, String type,
                                                   Integer month, Integer year,
                                                   String session) {
        Long schoolId = securityUtil.getSchoolId();
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        String currentClassName = student.getClassName();

        if ("month".equalsIgnoreCase(type)) {
            if (month == null || year == null) {
                throw new IllegalArgumentException("month and year are required when type=month");
            }
            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());

            // Use the student's joining date as the lower bound so a mid-month joiner
            // is not penalised for working days that existed before they enrolled.
            LocalDate effectiveStart = effectiveStart(student, start);
            LocalDate effectiveEnd = effectiveEnd(student, end);

            // Enrollment-authoritative resolution (E6C): partitions the denominator across any
            // mid-period class/section change using realized StudentEnrollment segments, excludes
            // authoritative enrollment gaps, and falls back to the legacy attendance-row bridge
            // (historicalClassNames/classMarkedDaysAcross) when no enrollment coverage exists at
            // all. See resolveHistoricalAttendance.
            HistoricalAttendanceResult result = resolveHistoricalAttendance(
                    schoolId, studentId, effectiveStart, effectiveEnd, currentClassName);

            long workingDays = result.workingDays().size();
            double absences = absenceEquivalent(result.countableRows());
            double present = Math.max(0, workingDays - absences);

            AttendanceSummaryDTO dto = new AttendanceSummaryDTO();
            dto.setStudentId(studentId);
            dto.setStudentName(student.getName());
            dto.setClassName(result.displayClassName());
            dto.setTotalWorkingDays(workingDays);
            dto.setDaysPresent(present);
            dto.setDaysAbsent(absences);
            dto.setAttendancePercentage(pct(present, workingDays));
            dto.setMonthlyBreakdown(null);
            return dto;

        } else if ("year".equalsIgnoreCase(type)) {
            if (session == null || session.isBlank()) {
                throw new IllegalArgumentException("session is required when type=year");
            }
            AcademicSession academicSession = academicSessionService.getSessionByLabel(schoolId, session)
                    .orElseThrow(() -> new IllegalArgumentException("No academic session found for label: " + session));
            LocalDate start = academicSession.getStartDate();
            LocalDate end   = academicSession.getEndDate();
            int startYear = start.getYear();
            int endYear = end.getYear();
            int startMonth = start.getMonthValue();

            LocalDate effectiveStart = effectiveStart(student, start);
            LocalDate effectiveEnd = effectiveEnd(student, end);
            HistoricalAttendanceResult result = resolveHistoricalAttendance(
                    schoolId, studentId, academicSession.getId(), effectiveStart, effectiveEnd, currentClassName);
            long totalWorkingDays = result.workingDays().size();
            double totalAbsences = absenceEquivalent(result.countableRows());
            double totalPresent = Math.max(0, totalWorkingDays - totalAbsences);

            // Monthly breakdown: iterate all 12 academic months in order for this school's calendar
            List<AttendanceSummaryDTO.MonthlyBreakdown> breakdown = new ArrayList<>();
            for (int am = 1; am <= 12; am++) {
                int calMonth = ((startMonth - 1 + am - 1) % 12) + 1;
                int calYear  = (calMonth >= startMonth) ? startYear : endYear;
                breakdown.add(buildMonthBreakdown(student, academicSession.getId(), calYear, calMonth));
            }

            AttendanceSummaryDTO dto = new AttendanceSummaryDTO();
            dto.setStudentId(studentId);
            dto.setStudentName(student.getName());
            dto.setClassName(result.displayClassName());
            dto.setTotalWorkingDays(totalWorkingDays);
            dto.setDaysPresent(totalPresent);
            dto.setDaysAbsent(totalAbsences);
            dto.setAttendancePercentage(pct(totalPresent, totalWorkingDays));
            dto.setMonthlyBreakdown(breakdown);
            return dto;

        } else {
            throw new IllegalArgumentException("type must be 'month' or 'year'");
        }
    }

    @Transactional(readOnly = true)
    public List<ClassAttendanceSummaryDTO> getClassSummary(String className, String type,
                                                            Integer month, Integer year,
                                                            String session, Long sectionId) {
        Long schoolId = securityUtil.getSchoolId();

        LocalDate start;
        LocalDate end;
        Long sessionId = null;

        if ("month".equalsIgnoreCase(type)) {
            if (month == null || year == null) {
                throw new IllegalArgumentException("month and year are required when type=month");
            }
            start = LocalDate.of(year, month, 1);
            end   = start.withDayOfMonth(start.lengthOfMonth());
            sessionId = resolveTenantSessionContaining(schoolId, start, end).map(AcademicSession::getId).orElse(null);
        } else if ("year".equalsIgnoreCase(type)) {
            if (session == null || session.isBlank()) {
                throw new IllegalArgumentException("session is required when type=year");
            }
            AcademicSession academicSession = academicSessionService.getSessionByLabel(schoolId, session)
                    .orElseThrow(() -> new IllegalArgumentException("No academic session found for label: " + session));
            start = academicSession.getStartDate();
            end   = academicSession.getEndDate();
            sessionId = academicSession.getId();
        } else {
            throw new IllegalArgumentException("type must be 'month' or 'year'");
        }

        // Legacy roster: current ACTIVE students in this class(+section) — always included so a
        // school with no StudentEnrollment data at all keeps working exactly as before.
        List<Student> liveStudents = (sectionId != null)
                ? studentRepository.findByClassNameAndSectionIdAndStatusAndSchoolId(className, sectionId, StudentStatus.ACTIVE, schoolId)
                : studentRepository.findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, schoolId);
        Map<String, Student> rosterStudents = new LinkedHashMap<>();
        liveStudents.forEach(s -> rosterStudents.put(s.getStudentId(), s));

        // Enrollment-authoritative roster augmentation (E6C): a student who was realized-enrolled
        // in this class(+section) at any point during the range — even if promoted, transferred,
        // or withdrawn since — must remain visible for the dates they were actually here.
        Long classId = schoolClassRepository.findBySchoolIdAndName(schoolId, className).map(SchoolClass::getId).orElse(null);
        if (sessionId != null && classId != null) {
            List<StudentEnrollment> enrollmentRows = (sectionId != null)
                    ? studentEnrollmentRepository.findRealizedByAcademicSessionAndClassAndSectionOverlappingRange(
                            schoolId, sessionId, classId, sectionId, start, end)
                    : studentEnrollmentRepository.findRealizedByAcademicSessionAndClassOverlappingRange(
                            schoolId, sessionId, classId, start, end);
            for (StudentEnrollment e : enrollmentRows) {
                rosterStudents.computeIfAbsent(e.getStudentId(),
                        sid -> studentRepository.findByStudentIdAndSchoolId(sid, schoolId).orElse(null));
            }
            rosterStudents.values().removeIf(Objects::isNull);
        }

        Long finalSessionId = sessionId;
        Long finalClassId = classId;
        List<ClassAttendanceSummaryDTO> result = rosterStudents.values().stream()
                .map(s -> {
                    LocalDate studentStart = effectiveStart(s, start);
                    LocalDate studentEnd = effectiveEnd(s, end);
                    HistoricalAttendanceResult r = resolveHistoricalAttendanceForClass(
                            schoolId, s.getStudentId(), finalSessionId, finalClassId, className, sectionId,
                            studentStart, studentEnd);
                    long workingDays = r.workingDays().size();
                    double absences = absenceEquivalent(r.countableRows());
                    double present  = Math.max(0, workingDays - absences);
                    return new ClassAttendanceSummaryDTO(
                            s.getStudentId(),
                            s.getName(),
                            className,
                            workingDays,
                            present,
                            absences,
                            pct(present, workingDays)
                    );
                })
                .sorted(Comparator.comparingDouble(ClassAttendanceSummaryDTO::getAttendancePercentage))
                .collect(Collectors.toList());

        log.info("Class summary for {} ({} {}): {} students", className, type, session != null ? session : month + "/" + year, result.size());
        return result;
    }

    /**
     * Same per-student attendance data as getClassSummary, but flattened across every
     * relevant class in the school in one call — avoids N separate per-class requests
     * for school-wide comparisons/low-attendance lookups.
     */
    @Transactional(readOnly = true)
    public List<ClassAttendanceSummaryDTO> getSchoolSummary(String type, Integer month, Integer year, String session) {
        Long schoolId = securityUtil.getSchoolId();
        Set<String> classNames = new LinkedHashSet<>(studentRepository.findDistinctActiveClassNamesBySchoolId(schoolId));

        // Enrollment-authoritative augmentation (E6C): a class with realized enrollment during
        // the requested historical period must appear here even if it currently has zero ACTIVE
        // students (e.g. phased out, merged, or every student since promoted/exited).
        LocalDate start = null;
        LocalDate end = null;
        Long sessionId = null;
        if ("month".equalsIgnoreCase(type) && month != null && year != null) {
            start = LocalDate.of(year, month, 1);
            end = start.withDayOfMonth(start.lengthOfMonth());
            sessionId = resolveTenantSessionContaining(schoolId, start, end).map(AcademicSession::getId).orElse(null);
        } else if ("year".equalsIgnoreCase(type) && session != null && !session.isBlank()) {
            Optional<AcademicSession> academicSession = academicSessionService.getSessionByLabel(schoolId, session);
            if (academicSession.isPresent()) {
                start = academicSession.get().getStartDate();
                end = academicSession.get().getEndDate();
                sessionId = academicSession.get().getId();
            }
        }
        if (sessionId != null) {
            for (StudentEnrollment e : studentEnrollmentRepository
                    .findRealizedByAcademicSessionOverlappingRange(schoolId, sessionId, start, end)) {
                if (e.getClassNameSnapshot() != null) classNames.add(e.getClassNameSnapshot());
            }
        }

        List<ClassAttendanceSummaryDTO> result = new ArrayList<>();
        for (String className : classNames) {
            result.addAll(getClassSummary(className, type, month, year, session, null));
        }
        return result;
    }

    /** Default lookback for consecutive-absence detection. Generous enough that any sane streak
     *  length still resolves across weekends/holidays/exam breaks, bounded so the query can never
     *  degrade into a full-session scan. */
    public static final int DEFAULT_ABSENCE_LOOKBACK_DAYS = 60;

    /**
     * Students absent on EVERY one of the most recent {@code minConsecutiveDays} marked school
     * days for this class — the "who's been absent the last 3 days" question.
     *
     * <p><b>Days are marked school days, never calendar days.</b> This is the whole subtlety of
     * the method. The attendance table stores a row only when a student was <i>absent</i>, plus a
     * sentinel {@code studentId = "X"} row written on every submission so that all-present days are
     * still visible (see getClassSummary / getDailyAttendance, which depend on the same trick).
     * So "school was open" == "a date appears in this table for the class", and a naive
     * today-minus-3-calendar-days window would sweep in weekends, holidays, and any day the
     * teacher simply hasn't marked yet — flagging students who were never absent at all and
     * emailing their parents a warning. Counting back through marked dates instead makes those
     * days structurally unrepresentable.
     *
     * <p>Reports the student's <i>true</i> streak, not merely that it met the minimum: a student
     * absent five days running is more urgent than one absent three, and the caller shouldn't have
     * to re-derive that. Cumulative session figures are folded in from getClassSummary rather than
     * recomputed here, so both views of a student always agree.
     *
     * @param minConsecutiveDays streak length required to be included (must be >= 1)
     * @param lookbackDays       calendar days back from today to consider; null uses the default
     * @param session            academic session for the cumulative figures (YYYY-YYYY)
     */
    @Transactional(readOnly = true)
    public List<ConsecutiveAbsenceDTO> getConsecutiveAbsentees(String className,
                                                               int minConsecutiveDays,
                                                               Integer lookbackDays,
                                                               String session) {
        return getConsecutiveAbsentees(className, minConsecutiveDays, lookbackDays, session, null);
    }

    @Transactional(readOnly = true)
    public List<ConsecutiveAbsenceDTO> getConsecutiveAbsentees(String className,
                                                               int minConsecutiveDays,
                                                               Integer lookbackDays,
                                                               String session,
                                                               Long sectionId) {
        if (minConsecutiveDays < 1) {
            throw new IllegalArgumentException("minConsecutiveDays must be at least 1");
        }
        Long schoolId = securityUtil.getSchoolId();
        int lookback = (lookbackDays != null && lookbackDays > 0) ? lookbackDays : DEFAULT_ABSENCE_LOOKBACK_DAYS;

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(lookback);

        // Single fetch: this same row set yields both the marked-school-day calendar (all rows,
        // 'X' sentinels included) and each student's absence dates ('X' excluded) — no second query.
        List<Attendance> rows = attendanceRepository
                .findByClassNameAndSchoolIdAndDateBetween(className, schoolId, start, end);

        // Most recent marked school day first.
        List<LocalDate> markedDaysDesc = rows.stream()
                .map(Attendance::getDate)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());

        if (markedDaysDesc.size() < minConsecutiveDays) {
            // Fewer marked days exist than the streak being asked about, so no streak of that
            // length can be *evidenced* — reporting students here would assert an absence on days
            // the school never recorded.
            log.info("Consecutive-absence check for class {}: only {} marked school day(s) in the last {} days, need {} — returning empty.",
                    className, markedDaysDesc.size(), lookback, minConsecutiveDays);
            return List.of();
        }

        Map<String, Set<LocalDate>> absencesByStudent = rows.stream()
                .filter(a -> !"X".equals(a.getStudentId()) && isFullAbsence(a))
                .collect(Collectors.groupingBy(Attendance::getStudentId,
                        Collectors.mapping(Attendance::getDate, Collectors.toSet())));

        // Cumulative figures come from the existing session computation so the two views agree.
        Map<String, ClassAttendanceSummaryDTO> sessionByStudent = new HashMap<>();
        if (session != null && !session.isBlank()) {
            for (ClassAttendanceSummaryDTO row : getClassSummary(className, "year", null, null, session, sectionId)) {
                sessionByStudent.put(row.getStudentId(), row);
            }
        }

        // ACTIVE-only — an exited student must never surface in a consecutive-absence report,
        // even if their old attendance rows are still present in the marked-day window above.
        List<Student> students = (sectionId != null)
                ? studentRepository.findByClassNameAndSectionIdAndStatusAndSchoolId(className, sectionId, StudentStatus.ACTIVE, schoolId)
                : studentRepository.findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, schoolId);
        List<ConsecutiveAbsenceDTO> result = new ArrayList<>();

        for (Student student : students) {
            Set<LocalDate> absentOn = absencesByStudent.get(student.getStudentId());
            if (absentOn == null || absentOn.isEmpty()) continue;  // never absent — not a streak of 0

            // Walk back from the most recent marked day until the first day they showed up.
            List<LocalDate> streak = new ArrayList<>();
            for (LocalDate day : markedDaysDesc) {
                if (!absentOn.contains(day)) break;
                streak.add(day);
            }
            if (streak.size() < minConsecutiveDays) continue;

            Collections.reverse(streak);  // oldest first, the order a human reads dates in
            ClassAttendanceSummaryDTO cumulative = sessionByStudent.get(student.getStudentId());

            result.add(new ConsecutiveAbsenceDTO(
                    student.getStudentId(),
                    student.getName(),
                    className,
                    streak.size(),
                    streak.stream().map(LocalDate::toString).collect(Collectors.toList()),
                    cumulative != null ? cumulative.getTotalWorkingDays() : 0L,
                    cumulative != null ? cumulative.getDaysPresent() : 0.0,
                    cumulative != null ? cumulative.getDaysAbsent() : 0.0,
                    cumulative != null ? cumulative.getAttendancePercentage() : 0.0
            ));
        }

        // Longest streak first — the most urgent cases lead.
        result.sort(Comparator.comparingInt(ConsecutiveAbsenceDTO::getConsecutiveAbsentDays).reversed());

        log.info("Consecutive-absence check for class {}: {} student(s) absent {}+ consecutive marked school days (of {} marked days in last {} days).",
                className, result.size(), minConsecutiveDays, markedDaysDesc.size(), lookback);
        return result;
    }

    /** Historically-correct attendance figures for a student over an explicit date range —
     *  the shared entry point other modules (currently ReportCardDataAssembler) should call
     *  instead of recreating their own current-class attendance calculation. Resolves class(es)
     *  from the student's own attendance rows in the range exactly like getStudentSummary (see
     *  historicalClassNames/resolveHistoricalClassName), so a report card's embedded attendance
     *  for an old session correctly reflects the class the student was actually in then. Takes
     *  an explicit range rather than a session label so the caller keeps its own session→date
     *  resolution (and whatever tolerance it has for a session with no matching AcademicSession
     *  row) — this method has no opinion on where start/end came from. */
    @Transactional(readOnly = true)
    public AttendanceSummaryDTO getStudentAttendanceForDateRange(String studentId, LocalDate start, LocalDate end) {
        Long schoolId = securityUtil.getSchoolId();
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        LocalDate effectiveStart = effectiveStart(student, start);
        LocalDate effectiveEnd = effectiveEnd(student, end);
        HistoricalAttendanceResult result = resolveHistoricalAttendance(
                schoolId, studentId, effectiveStart, effectiveEnd, student.getClassName());
        long workingDays = result.workingDays().size();
        double absences = absenceEquivalent(result.countableRows());
        double present = Math.max(0, workingDays - absences);

        AttendanceSummaryDTO dto = new AttendanceSummaryDTO();
        dto.setStudentId(studentId);
        dto.setStudentName(student.getName());
        dto.setClassName(result.displayClassName());
        dto.setTotalWorkingDays(workingDays);
        dto.setDaysPresent(present);
        dto.setDaysAbsent(absences);
        dto.setAttendancePercentage(pct(present, workingDays));
        return dto;
    }

    private AttendanceSummaryDTO.MonthlyBreakdown buildMonthBreakdown(Student student, Long sessionId, int year, int monthNum) {
        LocalDate start = LocalDate.of(year, monthNum, 1);
        LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());
        LocalDate effectiveStart = effectiveStart(student, start);
        LocalDate effectiveEnd = effectiveEnd(student, end);
        Long schoolId = securityUtil.getSchoolId();
        // Same enrollment-authoritative resolution as getStudentSummary — this method IS the
        // per-month figures inside a "year" summary, so it must never silently relabel a
        // promoted student's earlier months under their current class, nor drop a mid-month
        // class/section transition. sessionId is already known from the enclosing year call.
        HistoricalAttendanceResult result = resolveHistoricalAttendance(
                schoolId, student.getStudentId(), sessionId, effectiveStart, effectiveEnd, student.getClassName());
        long workingDays = result.workingDays().size();
        double absences = absenceEquivalent(result.countableRows());
        double present = Math.max(0, workingDays - absences);
        String monthName = Month.of(monthNum).getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        return new AttendanceSummaryDTO.MonthlyBreakdown(monthName, year, workingDays, present, absences, pct(present, workingDays));
    }

    @Transactional(readOnly = true)
    public DailyAttendanceDTO getDailyAttendance(String studentId, int month, int year) {
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());

        LocalDate effectiveStart = effectiveStart(student, start);
        LocalDate effectiveEnd = effectiveEnd(student, end);
        Long schoolId = securityUtil.getSchoolId();

        // Enrollment-authoritative resolution (E6C): school days = the union of marked days
        // across each realized enrollment segment's own class, intersected to that segment's
        // dates — never the student's CURRENT class for a historical period, and never a
        // different class's marked days bleeding into this student's own gap/other-class
        // dates. Falls back to the legacy attendance-row bridge when no enrollment coverage
        // exists. 'X' sentinel rows are preserved by classMarkedDaysAcross (see its Javadoc) —
        // without them, all-present days would be indistinguishable from holidays.
        HistoricalAttendanceResult result = resolveHistoricalAttendance(
                schoolId, studentId, effectiveStart, effectiveEnd, student.getClassName());
        List<String> schoolDays = result.workingDays().stream()
                .map(LocalDate::toString)
                .collect(Collectors.toList());

        List<String> absentDays = result.countableRows()
                .stream()
                .filter(this::isFullAbsence)
                .map(a -> a.getDate().toString())
                .sorted()
                .collect(Collectors.toList());
        Map<String, String> statuses = result.countableRows().stream()
                .filter(a -> a.getDate() != null)
                .collect(Collectors.toMap(a -> a.getDate().toString(),
                        a -> a.getStatus() == null ? "ABSENT" : a.getStatus().toUpperCase(Locale.ROOT),
                        (first, ignored) -> first));

        log.info("Daily attendance for student {} in {}/{}: {} school days, {} absent",
                studentId, month, year, schoolDays.size(), absentDays.size());
        School school = schoolRepository.findById(schoolId).orElse(null);
        List<String> nonWorkingDays = new ArrayList<>();
        if (school != null && school.getWorkingDays() != null && !school.getWorkingDays().isBlank()) {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                if (!isConfiguredWorkingDay(d, school.getWorkingDays())) nonWorkingDays.add(d.toString());
            }
        }
        return new DailyAttendanceDTO(schoolDays, absentDays, nonWorkingDays, statuses);
    }

    /** Round to 1 decimal place; returns 0.0 if workingDays is 0. */
    private double pct(double present, long workingDays) {
        if (workingDays == 0) return 0.0;
        return Math.round((double) present / workingDays * 1000.0) / 10.0;
    }

    private double absenceEquivalent(List<Attendance> rows) {
        return rows.stream().mapToDouble(this::absenceWeight).sum();
    }

    private double absenceWeight(Attendance attendance) {
        String status = attendance.getStatus();
        if (status == null || "ABSENT".equalsIgnoreCase(status)) return 1.0;
        if ("HALF_DAY".equalsIgnoreCase(status)) return 0.5;
        return 0.0; // PRESENT, LATE and EXCUSED do not reduce attendance percentage
    }

    private boolean isFullAbsence(Attendance attendance) {
        return attendance.getStatus() == null || "ABSENT".equalsIgnoreCase(attendance.getStatus());
    }

    private LocalDate effectiveStart(Student student, LocalDate periodStart) {
        return student.getJoiningDate() != null && student.getJoiningDate().isAfter(periodStart)
                ? student.getJoiningDate() : periodStart;
    }

    private LocalDate effectiveEnd(Student student, LocalDate periodEnd) {
        return student.getLeavingDate() != null && student.getLeavingDate().isBefore(periodEnd)
                ? student.getLeavingDate() : periodEnd;
    }

    // ─── E6C: enrollment-authoritative historical attendance resolution ───────
    //
    // StudentTemporalMembershipResolver (E6B) is the single source of truth for "who was
    // realized-enrolled (ACTIVE/CLOSED) in which class/section on which date." The methods
    // below are the ONLY place AttendanceService consults it — every reader (student summary,
    // daily attendance, counts, class/school summary, the report-card-facing range method)
    // goes through resolveHistoricalAttendance/resolveHistoricalAttendanceForClass rather than
    // querying enrollment or re-deriving temporal-membership semantics itself. When enrollment
    // coverage does not exist for a date (LEGACY_UNCOVERED — before the student's earliest
    // realized enrollment, or no AcademicSession/enrollment data at all), the original
    // attendance-row/live-Student bridge below (historicalClassNames/resolveHistoricalClassName/
    // classMarkedDaysAcross) is preserved unchanged. An AUTHORITATIVE_GAP (e.g. an exit/
    // readmission gap after enrollment adoption has begun) contributes zero working days and
    // zero present/absent — attendance evidence found inside such a gap is logged and preserved
    // in the database untouched, but never used to fabricate membership.

    /** Per-student result of enrollment-aware historical attendance resolution over a range:
     *  the countable working days, the student's own attendance rows restricted to dates that
     *  actually count (excludes authoritative-gap dates), and the single class/section to
     *  display — the segment effective at the end of the range, or the latest realized segment
     *  in the range if it ends inside a gap (see StudentTemporalMembershipResolver's ordering
     *  guarantee), or the legacy evidence-based resolution when no segment applies at all. */
    private record HistoricalAttendanceResult(
            List<LocalDate> workingDays, List<Attendance> countableRows,
            String displayClassName, Long displaySectionId) {}

    /** Resolves the tenant AcademicSession containing this student on {@code start}, via E6B —
     *  reused rather than re-implemented so "which session/is this ambiguous" logic lives in
     *  exactly one place. Empty means no session could be uniquely resolved (no AcademicSession
     *  configured for that date, or — pathologically — more than one), in which case callers
     *  fall back to the pre-E6C legacy bridge rather than fail the request. */
    private Optional<StudentTemporalMembershipResolver.Session> resolveStudentSession(
            Long schoolId, String studentId, LocalDate date) {
        try {
            return Optional.of(temporalMembershipResolver
                    .resolveEffectiveRealizedEnrollment(schoolId, studentId, date).session());
        } catch (NoSuchElementException | StudentTemporalMembershipResolver.TemporalMembershipConflictException e) {
            return Optional.empty();
        }
    }

    /** Same idea as resolveStudentSession but for class/school-level queries that have no
     *  particular student to anchor on (getClassSummary/getSchoolSummary for type=month) —
     *  looks up the tenant's own AcademicSession directly rather than going through the
     *  resolver's per-student entry point. Empty when no session uniquely contains the range. */
    private Optional<AcademicSession> resolveTenantSessionContaining(Long schoolId, LocalDate start, LocalDate end) {
        List<AcademicSession> matches = academicSessionRepository
                .findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(schoolId, start, start);
        if (matches.size() != 1) return Optional.empty();
        AcademicSession session = matches.get(0);
        if (end.isBefore(session.getStartDate()) || end.isAfter(session.getEndDate())) return Optional.empty();
        return Optional.of(session);
    }

    /** The unified entry point for per-student historical attendance over a date range —
     *  resolves the tenant session containing {@code start} itself. Prefer the overload below
     *  when the caller already knows the session (e.g. the "year" summary already resolved it
     *  by label), to avoid a redundant lookup and stay consistent with the caller's own session
     *  resolution. */
    private HistoricalAttendanceResult resolveHistoricalAttendance(
            Long schoolId, String studentId, LocalDate start, LocalDate end, String currentClassNameFallback) {
        return resolveHistoricalAttendance(schoolId, studentId, null, start, end, currentClassNameFallback);
    }

    private HistoricalAttendanceResult resolveHistoricalAttendance(
            Long schoolId, String studentId, Long knownSessionId, LocalDate start, LocalDate end,
            String currentClassNameFallback) {
        if (end.isBefore(start)) {
            return new HistoricalAttendanceResult(List.of(), List.of(), currentClassNameFallback, null);
        }

        Long sessionId = knownSessionId;
        if (sessionId == null) {
            Optional<StudentTemporalMembershipResolver.Session> sessionOpt =
                    resolveStudentSession(schoolId, studentId, start);
            if (sessionOpt.isEmpty()) {
                return legacyHistoricalAttendance(schoolId, studentId, start, end, currentClassNameFallback);
            }
            StudentTemporalMembershipResolver.Session session = sessionOpt.get();
            if (start.isBefore(session.startDate()) || end.isAfter(session.endDate())) {
                // Requested range isn't fully contained by the resolvable session (e.g. it
                // spans a session boundary) — fail safe to the legacy bridge for the whole
                // range rather than reject the request or guess a split point.
                return legacyHistoricalAttendance(schoolId, studentId, start, end, currentClassNameFallback);
            }
            sessionId = session.id();
        }

        StudentTemporalMembershipResolver.RangeResolution range;
        try {
            range = temporalMembershipResolver.resolveRealizedEnrollmentRange(schoolId, studentId, sessionId, start, end);
        } catch (RuntimeException e) {
            log.error("Falling back to legacy attendance evidence for student {} over {}..{}: " +
                    "could not resolve enrollment range.", studentId, start, end, e);
            return legacyHistoricalAttendance(schoolId, studentId, start, end, currentClassNameFallback);
        }

        if (range.classification() == StudentTemporalMembershipResolver.CoverageClassification.CONFLICT) {
            log.error("Ambiguous/conflicting enrollment data for student {} over {}..{}: {} — " +
                            "reporting zero historical attendance rather than guessing.",
                    studentId, start, end, range.conflictReason());
            return new HistoricalAttendanceResult(List.of(), List.of(), currentClassNameFallback, null);
        }

        List<LocalDate> workingDays = new ArrayList<>();
        List<Attendance> countableRows = new ArrayList<>();
        List<StudentTemporalMembershipResolver.Segment> orderedSegments = new ArrayList<>();

        for (StudentTemporalMembershipResolver.SegmentIntersection intersection : range.intersections()) {
            StudentTemporalMembershipResolver.Segment segment = intersection.segment();
            orderedSegments.add(segment);
            workingDays.addAll(classMarkedDaysAcross(Set.of(segment.classNameSnapshot()), schoolId,
                    intersection.intersectedFrom(), intersection.intersectedTo()));
            countableRows.addAll(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(
                    studentId, schoolId, intersection.intersectedFrom(), intersection.intersectedTo()));
        }

        for (StudentTemporalMembershipResolver.UncoveredInterval uncovered : range.uncoveredIntervals()) {
            List<Attendance> rowsInWindow = attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(
                    studentId, schoolId, uncovered.from(), uncovered.to());
            if (uncovered.classification() == StudentTemporalMembershipResolver.CoverageClassification.LEGACY_UNCOVERED) {
                Set<String> legacyClasses = historicalClassNames(rowsInWindow, currentClassNameFallback);
                workingDays.addAll(classMarkedDaysAcross(legacyClasses, schoolId, uncovered.from(), uncovered.to()));
                countableRows.addAll(rowsInWindow);
            } else if (!rowsInWindow.isEmpty()) {
                // AUTHORITATIVE_GAP: zero working days, zero present, zero absent. Stray
                // evidence is neither mutated nor deleted — just excluded from this calculation
                // and logged as inconsistent, per the exit/readmission-gap contract.
                log.warn("Attendance evidence exists for student {} within an authoritative enrollment gap " +
                                "{}..{} ({} row(s)) — preserving the rows but excluding them from historical " +
                                "attendance calculations.",
                        studentId, uncovered.from(), uncovered.to(), rowsInWindow.size());
            }
        }

        workingDays = workingDays.stream().distinct().sorted().collect(Collectors.toList());

        String displayClassName;
        Long displaySectionId;
        if (!orderedSegments.isEmpty()) {
            // range.intersections() is ordered ascending by effectiveFrom (see
            // StudentTemporalMembershipResolver), so the last element is both "effective at the
            // end of the range" (when it reaches end) and "latest realized segment in the range"
            // (when the range ends inside a gap) — the single display rule the spec calls for.
            StudentTemporalMembershipResolver.Segment last = orderedSegments.get(orderedSegments.size() - 1);
            displayClassName = last.classNameSnapshot();
            displaySectionId = last.sectionId();
        } else {
            List<Attendance> allRows = attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(studentId, schoolId, start, end);
            Set<String> legacyClasses = historicalClassNames(allRows, currentClassNameFallback);
            displayClassName = resolveHistoricalClassName(allRows, currentClassNameFallback, legacyClasses);
            displaySectionId = null;
        }

        return new HistoricalAttendanceResult(workingDays, countableRows, displayClassName, displaySectionId);
    }

    /** The pre-E6C bridge, preserved verbatim as the fallback for LEGACY_UNCOVERED periods and
     *  for schools/students with no resolvable enrollment/session context at all. */
    private HistoricalAttendanceResult legacyHistoricalAttendance(
            Long schoolId, String studentId, LocalDate start, LocalDate end, String currentClassNameFallback) {
        List<Attendance> rows = attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(studentId, schoolId, start, end);
        Set<String> historicalClasses = historicalClassNames(rows, currentClassNameFallback);
        String resolvedClassName = resolveHistoricalClassName(rows, currentClassNameFallback, historicalClasses);
        List<LocalDate> workingDays = classMarkedDaysAcross(historicalClasses, schoolId, start, end);
        return new HistoricalAttendanceResult(workingDays, rows, resolvedClassName, null);
    }

    /** Class/section-scoped variant of resolveHistoricalAttendance, for getClassSummary: unlike
     *  the per-student variant, the class is already known (the query parameter) rather than
     *  something to display, so this restricts contribution to segments/legacy evidence for
     *  exactly the requested classId(+sectionId) and returns zero for any other class/gap the
     *  student's own history shows in the range — a different class's days must never inflate
     *  or deflate THIS class's row for that student. */
    private HistoricalAttendanceResult resolveHistoricalAttendanceForClass(
            Long schoolId, String studentId, Long knownSessionId, Long classId, String className,
            Long sectionFilter, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            return new HistoricalAttendanceResult(List.of(), List.of(), className, sectionFilter);
        }
        if (knownSessionId == null) {
            return legacyHistoricalAttendanceForClass(schoolId, studentId, className, start, end);
        }

        StudentTemporalMembershipResolver.RangeResolution range;
        try {
            range = temporalMembershipResolver.resolveRealizedEnrollmentRange(schoolId, studentId, knownSessionId, start, end);
        } catch (RuntimeException e) {
            return legacyHistoricalAttendanceForClass(schoolId, studentId, className, start, end);
        }

        if (range.classification() == StudentTemporalMembershipResolver.CoverageClassification.CONFLICT) {
            log.error("Ambiguous/conflicting enrollment data for student {} in class {} over {}..{}: {} — " +
                            "excluding from class summary rather than guessing.",
                    studentId, className, start, end, range.conflictReason());
            return new HistoricalAttendanceResult(List.of(), List.of(), className, sectionFilter);
        }

        List<LocalDate> workingDays = new ArrayList<>();
        List<Attendance> countableRows = new ArrayList<>();

        for (StudentTemporalMembershipResolver.SegmentIntersection intersection : range.intersections()) {
            StudentTemporalMembershipResolver.Segment segment = intersection.segment();
            boolean classMatches = Objects.equals(segment.classId(), classId);
            boolean sectionMatches = sectionFilter == null || Objects.equals(segment.sectionId(), sectionFilter);
            if (!classMatches || !sectionMatches) continue;
            workingDays.addAll(classMarkedDaysAcross(Set.of(segment.classNameSnapshot()), schoolId,
                    intersection.intersectedFrom(), intersection.intersectedTo()));
            countableRows.addAll(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(
                    studentId, schoolId, intersection.intersectedFrom(), intersection.intersectedTo()));
        }

        for (StudentTemporalMembershipResolver.UncoveredInterval uncovered : range.uncoveredIntervals()) {
            if (uncovered.classification() != StudentTemporalMembershipResolver.CoverageClassification.LEGACY_UNCOVERED) {
                List<Attendance> gapRows = attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(
                        studentId, schoolId, uncovered.from(), uncovered.to());
                if (!gapRows.isEmpty()) {
                    log.warn("Attendance evidence exists for student {} within an authoritative enrollment gap " +
                                    "{}..{} — preserving the rows but excluding them from the class summary for {}.",
                            studentId, uncovered.from(), uncovered.to(), className);
                }
                continue;
            }
            // LEGACY_UNCOVERED: no enrollment coverage for this sub-window at all, so bridge to
            // the requested class's own marked days for it — the same rule getClassSummary has
            // always applied for schools with no enrollment data.
            workingDays.addAll(classMarkedDaysAcross(Set.of(className), schoolId, uncovered.from(), uncovered.to()));
            countableRows.addAll(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(
                    studentId, schoolId, uncovered.from(), uncovered.to()));
        }

        workingDays = workingDays.stream().distinct().sorted().collect(Collectors.toList());
        return new HistoricalAttendanceResult(workingDays, countableRows, className, sectionFilter);
    }

    private HistoricalAttendanceResult legacyHistoricalAttendanceForClass(
            Long schoolId, String studentId, String className, LocalDate start, LocalDate end) {
        List<Attendance> rows = attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(studentId, schoolId, start, end);
        List<LocalDate> workingDays = classMarkedDaysAcross(Set.of(className), schoolId, start, end);
        return new HistoricalAttendanceResult(workingDays, rows, className, null);
    }

    // ─── Historical class resolution ─────────────────────────────────────────
    //
    // Attendance rows snapshot className/classId/sectionId at mark time and are never
    // rewritten later — promotion (StudentPromotionService) only ever updates the live
    // Student row. So for any historical period, "what class was this for" must come from
    // the student's OWN attendance rows in that period, never from student.getClassName()
    // (their CURRENT class) — that was the exact bug: a promoted student's old attendance
    // summaries/daily views/counts silently searched for THEIR NEW class's marked days
    // against OLD dates, when the rows are (correctly, and permanently) still labeled with
    // the old class. No student_enrollment table exists yet to look this up structurally —
    // these helpers derive it deterministically from the data that's already there.

    /** The class name(s) a student's own attendance rows show within a period. The
     *  overwhelmingly common result is a single class. Falls back to the student's current
     *  className only when they have no attendance rows at all in the period (nothing to
     *  derive from — e.g. before attendance was ever marked for them, or a period before
     *  they joined). More than one distinct name means a genuine mid-period class change —
     *  handled explicitly by resolveHistoricalClassName / classMarkedDaysAcross below, never
     *  silently collapsed to one guess. */
    private Set<String> historicalClassNames(List<Attendance> studentRowsInPeriod, String fallbackClassName) {
        Set<String> names = studentRowsInPeriod.stream()
                .map(Attendance::getClassName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return names.isEmpty() ? new LinkedHashSet<>(Set.of(fallbackClassName)) : names;
    }

    /** The single class name to report for a period (e.g. AttendanceSummaryDTO.className).
     *  The common one-class case returns it directly. A genuine mid-period class change is
     *  not silently guessed: it's logged with every class name involved, and the class shown
     *  is the one on the most recently dated row in the period — the most decision-relevant
     *  single label when a caller needs exactly one. The working-day COUNT for the period is
     *  unaffected by this choice; see classMarkedDaysAcross, which covers every class involved
     *  regardless of which one is shown here. */
    private String resolveHistoricalClassName(List<Attendance> studentRowsInPeriod, String fallbackClassName, Set<String> classNames) {
        if (classNames.size() <= 1) {
            return classNames.isEmpty() ? fallbackClassName : classNames.iterator().next();
        }
        log.warn("Student attendance rows show {} different classes ({}) within one requested historical period — " +
                        "a genuine mid-period class change with no student_enrollment table yet to disambiguate by date. " +
                        "Reporting the most recently marked class for display; working-day totals still cover every class involved.",
                classNames.size(), classNames);
        return studentRowsInPeriod.stream()
                .filter(a -> a.getClassName() != null)
                .max(Comparator.comparing(Attendance::getDate))
                .map(Attendance::getClassName)
                .orElse(fallbackClassName);
    }

    /** Distinct marked school days across every class in the set, for the given period —
     *  the correct working-day denominator even when a student's own rows span more than one
     *  class (see historicalClassNames): a working day the school actually held is a working
     *  day regardless of which class label it was marked under, so every class the student
     *  was actually in during the period contributes its marked days, unioned (not summed —
     *  Set/distinct — so a day both classes happened to mark on isn't double-counted). The
     *  common single-class case is just that one class's marked days, unchanged from before. */
    private List<LocalDate> classMarkedDaysAcross(Set<String> classNames, Long schoolId, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) return List.of();
        return classNames.stream()
                .flatMap(cn -> attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(cn, schoolId, start, end).stream())
                .map(Attendance::getDate)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private boolean isConfiguredWorkingDay(LocalDate date, String workingDays) {
        if (workingDays == null || workingDays.isBlank()) return false;
        return Arrays.stream(workingDays.split(","))
                .map(String::trim)
                .anyMatch(day -> date.getDayOfWeek().name().equalsIgnoreCase(day));
    }
}
