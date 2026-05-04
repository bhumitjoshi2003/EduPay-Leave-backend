package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.DailyAttendanceDTO;
import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AttendanceRepository;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);

    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    @Transactional
    public void saveAttendance(List<Attendance> attendanceList, HttpServletRequest request) {

        if (attendanceList == null || attendanceList.isEmpty()) {
            log.warn("Attempted to save empty or null attendance list.");
            return;
        }

        LocalDate absentDate = attendanceList.getFirst().getDate();
        String className = attendanceList.getFirst().getClassName();

        log.info("Saving attendance for date: {} and class: {}", absentDate, className);

        try {
            Long schoolId = securityUtil.getSchoolId();

            // Set schoolId on each attendance record before saving
            for (Attendance a : attendanceList) {
                a.setSchoolId(schoolId);
            }

            // Capture old state before deletion
            List<Attendance> oldRecords =
                    attendanceRepository.findByDateAndClassNameAndSchoolId(absentDate, className, schoolId);

            String oldValue = objectMapper.writeValueAsString(oldRecords);

            attendanceRepository.deleteByDateAndClassNameAndSchoolId(absentDate, className, schoolId);
            attendanceRepository.saveAll(attendanceList);

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
        if (absentDate == null || className == null || className.trim().isEmpty()) {
            log.warn("Attempted to fetch attendance with null date or empty class name.");
            return Collections.emptyList();
        }
        log.info("Fetching attendance for date: {} and class: {}", absentDate, className);
        try {
            List<Attendance> attendanceList = attendanceRepository.findByDateAndClassNameAndSchoolId(absentDate, className, securityUtil.getSchoolId());
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
            Optional<Student> studentOptional = studentRepository.findById(studentId);
            if (studentOptional.isEmpty()) {
                log.warn("Student not found with ID: {}", studentId);
                return counts;
            }
            student = studentOptional.get();
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
            return studentRepository.findById(studentId)
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
            int endYear = Integer.parseInt(years[1]);

            long count = attendanceRepository.countUnappliedLeavesForAcademicYear(studentId, securityUtil.getSchoolId(), startYear, endYear);
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
            int endYear = Integer.parseInt(years[1]);

            LocalDate startDate = LocalDate.of(startYear, 4, 1);
            LocalDate endDate = LocalDate.of(endYear, 3, 31);

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

        if (date == null || className == null || className.trim().isEmpty()) {
            log.warn("Invalid input for deleteAttendanceByDateAndClass.");
            return;
        }

        try {
            Long schoolId = securityUtil.getSchoolId();
            List<Attendance> oldRecords =
                    attendanceRepository.findByDateAndClassNameAndSchoolId(date, className, schoolId);

            String oldValue = objectMapper.writeValueAsString(oldRecords);

            attendanceRepository.deleteByDateAndClassNameAndSchoolId(date, className, schoolId);

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
            LocalDate joiningDate    = student.getJoiningDate();
            LocalDate effectiveStart = (joiningDate != null && joiningDate.isAfter(start))
                    ? joiningDate : start;

            // NOTE: DO NOT filter 'X' from countDistinctWorkingDays — see getClassSummary.
            long workingDays = attendanceRepository.countDistinctWorkingDays(className, securityUtil.getSchoolId(), effectiveStart, end);
            long absences    = attendanceRepository.countByStudentIdAndSchoolIdAndDateBetween(studentId, securityUtil.getSchoolId(), start, end);
            long present     = Math.max(0, workingDays - absences);

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
            LocalDate start = LocalDate.of(startYear, 4, 1);
            LocalDate end   = LocalDate.of(endYear, 3, 31);

            long totalWorkingDays = attendanceRepository.countDistinctWorkingDays(className, securityUtil.getSchoolId(), start, end);
            long totalAbsences    = attendanceRepository.countByStudentIdAndSchoolIdAndDateBetween(studentId, securityUtil.getSchoolId(), start, end);
            long totalPresent     = Math.max(0, totalWorkingDays - totalAbsences);

            // Monthly breakdown: Apr(startYear)…Dec(startYear), Jan(endYear)…Mar(endYear)
            List<AttendanceSummaryDTO.MonthlyBreakdown> breakdown = new ArrayList<>();
            for (int m = 4; m <= 12; m++) {
                breakdown.add(buildMonthBreakdown(studentId, className, startYear, m));
            }
            for (int m = 1; m <= 3; m++) {
                breakdown.add(buildMonthBreakdown(studentId, className, endYear, m));
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
                                                            String session) {
        List<Student> students = studentRepository.findByClassNameAndSchoolId(className, securityUtil.getSchoolId());

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
            int endYear   = Integer.parseInt(session.substring(5));
            start = LocalDate.of(startYear, 4, 1);
            end   = LocalDate.of(endYear, 3, 31);
        } else {
            throw new IllegalArgumentException("type must be 'month' or 'year'");
        }

        // Fetch all absences for the class in one query, group by studentId.
        // 'X' rows (studentId = "X") are excluded here — they are sentinel records used
        // to mark all-present days and must never appear in a student's absence count.
        List<Attendance> allAbsences = attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(className, securityUtil.getSchoolId(), start, end);
        Map<String, Long> absencesByStudent = allAbsences.stream()
                .filter(a -> !"X".equals(a.getStudentId()))
                .collect(Collectors.groupingBy(Attendance::getStudentId, Collectors.counting()));

        // NOTE: 'X' sentinel rows (studentId = "X") are inserted by the frontend whenever
        // attendance is submitted, including all-present days. These rows are essential —
        // they make all-present days visible to this COUNT(DISTINCT date) query.
        // DO NOT filter out 'X' from countDistinctWorkingDays.
        long workingDays = attendanceRepository.countDistinctWorkingDays(className, securityUtil.getSchoolId(), start, end);

        List<ClassAttendanceSummaryDTO> result = students.stream()
                .map(s -> {
                    long absences = absencesByStudent.getOrDefault(s.getStudentId(), 0L);
                    long present  = Math.max(0, workingDays - absences);
                    return new ClassAttendanceSummaryDTO(
                            s.getStudentId(),
                            s.getName(),
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

    private AttendanceSummaryDTO.MonthlyBreakdown buildMonthBreakdown(String studentId, String className,
                                                                       int year, int monthNum) {
        LocalDate start = LocalDate.of(year, monthNum, 1);
        LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());

        long workingDays = attendanceRepository.countDistinctWorkingDays(className, securityUtil.getSchoolId(), start, end);
        long absences    = attendanceRepository.countByStudentIdAndSchoolIdAndDateBetween(studentId, securityUtil.getSchoolId(), start, end);
        long present     = Math.max(0, workingDays - absences);
        String monthName = Month.of(monthNum).getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        return new AttendanceSummaryDTO.MonthlyBreakdown(monthName, year, workingDays, present, absences, pct(present, workingDays));
    }

    @Transactional(readOnly = true)
    public DailyAttendanceDTO getDailyAttendance(String studentId, int month, int year) {
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());

        // School days = distinct dates on which attendance was submitted for this class.
        // 'X' rows (studentId = "X") are inserted by the frontend on all-present days,
        // so they intentionally make those days visible here as "school was open".
        // Do NOT filter out 'X' from this query — without it, all-present days would
        // be indistinguishable from holidays.
        Long schoolId = securityUtil.getSchoolId();
        List<String> schoolDays = attendanceRepository
                .findByClassNameAndSchoolIdAndDateBetween(student.getClassName(), schoolId, start, end)
                .stream()
                .map(a -> a.getDate().toString())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // Absent days = dates this student was marked absent
        List<String> absentDays = attendanceRepository
                .findByStudentIdAndSchoolIdAndDateBetween(studentId, schoolId, start, end)
                .stream()
                .map(a -> a.getDate().toString())
                .sorted()
                .collect(Collectors.toList());

        log.info("Daily attendance for student {} in {}/{}: {} school days, {} absent",
                studentId, month, year, schoolDays.size(), absentDays.size());
        return new DailyAttendanceDTO(schoolDays, absentDays);
    }

    /** Round to 1 decimal place; returns 0.0 if workingDays is 0. */
    private double pct(long present, long workingDays) {
        if (workingDays == 0) return 0.0;
        return Math.round((double) present / workingDays * 1000.0) / 10.0;
    }
}