package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ConsecutiveAbsenceDTO;
import com.indraacademy.ias_management.dto.DailyAttendanceDTO;
import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
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
                Set<String> sectionStudentIds = studentRepository
                        .findByClassNameAndSectionIdAndSchoolId(className, sectionId, schoolId).stream()
                        .map(Student::getStudentId).collect(Collectors.toSet());
                attendanceList = attendanceList.stream()
                        .filter(a -> sectionStudentIds.contains(a.getStudentId())
                                || ("X".equals(a.getStudentId())
                                && (a.getSectionId() == null || Objects.equals(sectionId, a.getSectionId()))))
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

        Student student;
        String className;
        try {
            Optional<Student> studentOptional = studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId());
            if (studentOptional.isEmpty()) {
                log.warn("Student not found with ID: {}", studentId);
                return counts;
            }
            student = studentOptional.orElseThrow(() -> new IllegalArgumentException("Student not found: " + studentId));
            className = student.getClassName();
        } catch (DataAccessException e) {
            log.error("Data access error fetching student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve student info for counts", e);
        }

        LocalDate studentJoiningDate = student.getJoiningDate();
        LocalDate studentLeavingDate = student.getLeavingDate();

        long studentAbsentCount;
        long totalWorkingDays;

        try {
            studentAbsentCount = attendanceRepository.countAbsences(studentId, securityUtil.getSchoolId(), year, month);
            // dummy student "X" = total working days (days school was open)
            totalWorkingDays = attendanceRepository.countWorkingDaysForClass(className, securityUtil.getSchoolId(), year, month);
        } catch (DataAccessException e) {
            log.error("Data access error calculating absence counts for student ID: {}", studentId, e);
            throw new RuntimeException("Could not calculate attendance counts", e);
        }

        // Student joined after this month?
        boolean joinedAfterMonth = (studentJoiningDate != null) &&
                (studentJoiningDate.getYear() > year ||
                        (studentJoiningDate.getYear() == year && studentJoiningDate.getMonthValue() > month));

        // Student left before this month?
        boolean leftBeforeMonth = (studentLeavingDate != null) &&
                (studentLeavingDate.getYear() < year ||
                        (studentLeavingDate.getYear() == year && studentLeavingDate.getMonthValue() < month));

        // If no joining date, or joined after this month, or left before this month → no data
        if (studentJoiningDate == null || joinedAfterMonth || leftBeforeMonth) {
            studentAbsentCount = 0L;
            totalWorkingDays   = 0L;
            log.info("Student ID: {} was not active in {}/{}. Counts set to 0.", studentId, month, year);
        } else {
            // ✅ Joined in the same month/year → exclude days before joining
            if (studentJoiningDate.getYear() == year &&
                    studentJoiningDate.getMonthValue() == month) {

                LocalDate joinDate = studentJoiningDate;
                try {
                    long daysBeforeJoin = attendanceRepository.countWorkingDaysBeforeJoin(className, securityUtil.getSchoolId(), year, month, joinDate);
                    totalWorkingDays -= daysBeforeJoin;
                    log.debug("Adjusted total working days for student ID: {} due to mid-month joining. Adjusted by: {}",
                            studentId, daysBeforeJoin);
                } catch (DataAccessException e) {
                    log.error("Data access error adjusting total working days (before join) for student ID: {}", studentId, e);
                }
            }

            // ✅ Left in the same month/year → exclude days after leaving
            if (studentLeavingDate != null &&
                    studentLeavingDate.getYear() == year &&
                    studentLeavingDate.getMonthValue() == month) {

                LocalDate leaveDate = studentLeavingDate;
                try {
                    long daysAfterLeave = attendanceRepository.countWorkingDaysAfterLeave(className, securityUtil.getSchoolId(), year, month, leaveDate);
                    totalWorkingDays -= daysAfterLeave;
                    log.debug("Adjusted total working days for student ID: {} due to mid-month leaving. Adjusted by: {}",
                            studentId, daysAfterLeave);
                } catch (DataAccessException e) {
                    log.error("Data access error adjusting total working days (after leave) for student ID: {}", studentId, e);
                }
            }
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
            String[] years = session.split("-");
            if (years.length != 2) {
                log.error("Invalid session format: {}", session);
                return 0L;
            }
            int startYear = Integer.parseInt(years[0]);
            int startMonth = schoolRepository.findById(securityUtil.getSchoolId())
                    .map(School::getAcademicYearStartMonth).orElse(4);
            LocalDate startDate = LocalDate.of(startYear, startMonth, 1);
            LocalDate endDate = startDate.plusYears(1).minusDays(1);

            long count = attendanceRepository.countUnappliedLeavesForAcademicYear(studentId, securityUtil.getSchoolId(), startDate, endDate);
            log.info("Total unapplied leave count for student ID: {} is {}", studentId, count);
            return count;
        } catch (NumberFormatException e) {
            log.error("Error parsing session year for session: {}", session, e);
            return 0L;
        } catch (DataAccessException e) {
            log.error("Data access error fetching unapplied leave count for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve unapplied leave count", e);
        }
    }

    @Transactional
    public void updateChargePaidAfterPayment(String studentId,
                                             String session,
                                             HttpServletRequest request) {

        if (studentId == null || studentId.trim().isEmpty()
                || session == null || session.trim().isEmpty()) {
            log.warn("Invalid input for updateChargePaidAfterPayment.");
            return;
        }

        try {
            String[] years = session.split("-");
            int startYear = Integer.parseInt(years[0]);

            int startMonth = schoolRepository.findById(securityUtil.getSchoolId())
                    .map(s -> s.getAcademicYearStartMonth()).orElse(4);
            LocalDate startDate = LocalDate.of(startYear, startMonth, 1);
            LocalDate endDate = startDate.plusYears(1).minusDays(1);

            attendanceRepository.updateChargePaidForSession(studentId, securityUtil.getSchoolId(), startDate, endDate);

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
    public void updateChargePaidAfterPayment(String studentId, String session) {
        updateChargePaidAfterPayment(studentId, session, null);
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
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        String className = student.getClassName();

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

            // NOTE: DO NOT filter 'X' from countDistinctWorkingDays — see getClassSummary.
            long workingDays = countClassMarkedDays(student.getClassName(), securityUtil.getSchoolId(), effectiveStart, effectiveEnd);
            double absences = absenceEquivalent(attendanceRepository
                    .findByStudentIdAndSchoolIdAndDateBetween(studentId, securityUtil.getSchoolId(), effectiveStart, effectiveEnd));
            double present = Math.max(0, workingDays - absences);

            AttendanceSummaryDTO dto = new AttendanceSummaryDTO();
            dto.setStudentId(studentId);
            dto.setStudentName(student.getName());
            dto.setClassName(className);
            dto.setTotalWorkingDays(workingDays);
            dto.setDaysPresent(present);
            dto.setDaysAbsent(absences);
            dto.setAttendancePercentage(pct(present, workingDays));
            dto.setMonthlyBreakdown(null);
            return dto;

        } else if ("year".equalsIgnoreCase(type)) {
            if (session == null || !session.matches("\\d{4}-\\d{4}")) {
                throw new IllegalArgumentException("session is required in format YYYY-YYYY when type=year");
            }
            int startYear = Integer.parseInt(session.substring(0, 4));
            int endYear   = Integer.parseInt(session.substring(5));
            int startMonth = schoolRepository.findById(securityUtil.getSchoolId())
                    .map(s -> s.getAcademicYearStartMonth()).orElse(4);
            LocalDate start = LocalDate.of(startYear, startMonth, 1);
            LocalDate end   = start.plusYears(1).minusDays(1);

            LocalDate effectiveStart = effectiveStart(student, start);
            LocalDate effectiveEnd = effectiveEnd(student, end);
            long totalWorkingDays = countClassMarkedDays(student.getClassName(), securityUtil.getSchoolId(), effectiveStart, effectiveEnd);
            double totalAbsences = absenceEquivalent(attendanceRepository
                    .findByStudentIdAndSchoolIdAndDateBetween(studentId, securityUtil.getSchoolId(), effectiveStart, effectiveEnd));
            double totalPresent = Math.max(0, totalWorkingDays - totalAbsences);

            // Monthly breakdown: iterate all 12 academic months in order for this school's calendar
            List<AttendanceSummaryDTO.MonthlyBreakdown> breakdown = new ArrayList<>();
            for (int am = 1; am <= 12; am++) {
                int calMonth = ((startMonth - 1 + am - 1) % 12) + 1;
                int calYear  = (calMonth >= startMonth) ? startYear : endYear;
                breakdown.add(buildMonthBreakdown(student, calYear, calMonth));
            }

            AttendanceSummaryDTO dto = new AttendanceSummaryDTO();
            dto.setStudentId(studentId);
            dto.setStudentName(student.getName());
            dto.setClassName(className);
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
        List<Student> students = (sectionId != null)
                ? studentRepository.findByClassNameAndSectionIdAndSchoolId(className, sectionId, schoolId)
                : studentRepository.findByClassNameAndSchoolId(className, schoolId);

        LocalDate start;
        LocalDate end;

        if ("month".equalsIgnoreCase(type)) {
            if (month == null || year == null) {
                throw new IllegalArgumentException("month and year are required when type=month");
            }
            start = LocalDate.of(year, month, 1);
            end   = start.withDayOfMonth(start.lengthOfMonth());
        } else if ("year".equalsIgnoreCase(type)) {
            if (session == null || !session.matches("\\d{4}-\\d{4}")) {
                throw new IllegalArgumentException("session is required in format YYYY-YYYY when type=year");
            }
            int startYear = Integer.parseInt(session.substring(0, 4));
            int startMonth = schoolRepository.findById(securityUtil.getSchoolId())
                    .map(s -> s.getAcademicYearStartMonth()).orElse(4);
            start = LocalDate.of(startYear, startMonth, 1);
            end   = start.plusYears(1).minusDays(1);
        } else {
            throw new IllegalArgumentException("type must be 'month' or 'year'");
        }

        // Fetch all absences for the class in one query, group by studentId.
        // 'X' rows (studentId = "X") are excluded here — they are sentinel records used
        // to mark all-present days and must never appear in a student's absence count.
        List<Attendance> allAbsences = attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(className, securityUtil.getSchoolId(), start, end);
        Map<String, Double> absencesByStudent = allAbsences.stream()
                .filter(a -> !"X".equals(a.getStudentId()))
                .collect(Collectors.groupingBy(Attendance::getStudentId,
                        Collectors.summingDouble(this::absenceWeight)));

        // NOTE: 'X' sentinel rows (studentId = "X") are inserted by the frontend whenever
        // attendance is submitted, including all-present days. These rows are essential —
        // they make all-present days visible to this COUNT(DISTINCT date) query.
        // DO NOT filter out 'X' from countDistinctWorkingDays.
        Set<LocalDate> markedClassDays = allAbsences.stream().map(Attendance::getDate).collect(Collectors.toSet());

        List<ClassAttendanceSummaryDTO> result = students.stream()
                .map(s -> {
                    LocalDate studentStart = effectiveStart(s, start);
                    LocalDate studentEnd = effectiveEnd(s, end);
                    long workingDays = markedClassDays.stream()
                            .filter(d -> !d.isBefore(studentStart) && !d.isAfter(studentEnd)).count();
                    double absences = absencesByStudent.getOrDefault(s.getStudentId(), 0.0);
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
     * active class in the school in one call — avoids N separate per-class requests
     * for school-wide comparisons/low-attendance lookups.
     */
    @Transactional(readOnly = true)
    public List<ClassAttendanceSummaryDTO> getSchoolSummary(String type, Integer month, Integer year, String session) {
        Long schoolId = securityUtil.getSchoolId();
        List<String> classNames = studentRepository.findDistinctActiveClassNamesBySchoolId(schoolId);

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
            for (ClassAttendanceSummaryDTO row : getClassSummary(className, "year", null, null, session, null)) {
                sessionByStudent.put(row.getStudentId(), row);
            }
        }

        List<Student> students = studentRepository.findByClassNameAndSchoolId(className, schoolId);
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

    private AttendanceSummaryDTO.MonthlyBreakdown buildMonthBreakdown(Student student, int year, int monthNum) {
        LocalDate start = LocalDate.of(year, monthNum, 1);
        LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());
        LocalDate effectiveStart = effectiveStart(student, start);
        LocalDate effectiveEnd = effectiveEnd(student, end);
        long workingDays = countClassMarkedDays(student.getClassName(), securityUtil.getSchoolId(), effectiveStart, effectiveEnd);
        double absences = absenceEquivalent(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(
                student.getStudentId(), securityUtil.getSchoolId(), effectiveStart, effectiveEnd));
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

        // School days = distinct dates on which this student's class submitted attendance.
        // Another class being marked must never make this student appear present.
        // 'X' rows (studentId = "X") are inserted by the frontend on all-present days,
        // so they intentionally make those days visible here as "school was open".
        // Do NOT filter out 'X' from this query — without it, all-present days would
        // be indistinguishable from holidays.
        Long schoolId = securityUtil.getSchoolId();
        List<String> schoolDays = attendanceRepository
                .findByClassNameAndSchoolIdAndDateBetween(student.getClassName(), schoolId, effectiveStart, effectiveEnd)
                .stream()
                .map(a -> a.getDate().toString())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // Absent days = dates this student was marked absent
        List<Attendance> studentRows = attendanceRepository
                .findByStudentIdAndSchoolIdAndDateBetween(studentId, schoolId, effectiveStart, effectiveEnd);
        List<String> absentDays = studentRows
                .stream()
                .filter(this::isFullAbsence)
                .map(a -> a.getDate().toString())
                .sorted()
                .collect(Collectors.toList());
        Map<String, String> statuses = studentRows.stream()
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

    private long countClassMarkedDays(String className, Long schoolId, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) return 0;
        return attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(className, schoolId, start, end).stream()
                .map(Attendance::getDate).distinct().count();
    }

    private boolean isConfiguredWorkingDay(LocalDate date, String workingDays) {
        if (workingDays == null || workingDays.isBlank()) return false;
        return Arrays.stream(workingDays.split(","))
                .map(String::trim)
                .anyMatch(day -> date.getDayOfWeek().name().equalsIgnoreCase(day));
    }
}
