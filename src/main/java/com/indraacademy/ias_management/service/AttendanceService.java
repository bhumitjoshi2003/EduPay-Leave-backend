package com.indraacademy.ias_management.service;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            // Capture old state before deletion
            List<Attendance> oldRecords =
                    attendanceRepository.findByDateAndClassName(absentDate, className);

            String oldValue = objectMapper.writeValueAsString(oldRecords);

            attendanceRepository.deleteByDateAndClassName(absentDate, className);
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

    public List<Attendance> getAttendanceByDateAndClass(LocalDate absentDate, String className) {
        if (absentDate == null || className == null || className.trim().isEmpty()) {
            log.warn("Attempted to fetch attendance with null date or empty class name.");
            return Collections.emptyList();
        }
        log.info("Fetching attendance for date: {} and class: {}", absentDate, className);
        try {
            List<Attendance> attendanceList = attendanceRepository.findByDateAndClassName(absentDate, className);
            log.info("Found {} attendance records for date: {} and class: {}", attendanceList.size(), absentDate, className);
            return attendanceList;
        } catch (DataAccessException e) {
            log.error("Data access error fetching attendance for date {} and class {}", absentDate, className, e);
            throw new RuntimeException("Could not retrieve attendance due to data access issue", e);
        }
    }

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
            studentAbsentCount = attendanceRepository.countAbsences(studentId, year, month);
            // dummy student "X" = total working days (days school was open)
            totalWorkingDays = attendanceRepository.countWorkingDaysForClass(className, year, month);
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
                    long daysBeforeJoin = attendanceRepository.countWorkingDaysBeforeJoin(className, year, month, joinDate);
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
                    long daysAfterLeave = attendanceRepository.countWorkingDaysAfterLeave(className, year, month, leaveDate);
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

            long count = attendanceRepository.countUnappliedLeavesForAcademicYear(studentId, startYear, endYear);
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

            attendanceRepository.updateChargePaidForSession(studentId, startDate, endDate);

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
            List<Attendance> oldRecords =
                    attendanceRepository.findByDateAndClassName(date, className);

            String oldValue = objectMapper.writeValueAsString(oldRecords);

            attendanceRepository.deleteByDateAndClassName(date, className);

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

    public List<Attendance> getAttendanceByStudentMonthAndYear(String studentId, int year, int month) {
        log.info("Fetching monthly attendance for Student: {} for {}-{}", studentId, year, month);
        try {
            LocalDate startDate = LocalDate.of(year, month, 1);
            LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());

            return attendanceRepository.findByStudentIdAndDateRange(studentId, startDate, endDate);
        } catch (Exception e) {
            log.error("Error fetching monthly attendance for student: {}", studentId, e);
            throw new RuntimeException("Could not retrieve monthly attendance");
        }
    }

    public List<Attendance> getAttendanceByStudentClassMonthAndYear(String studentId, String className, int year, int month) {
        log.info("Fetching monthly attendance for Student: {} in Class: {} for {}-{}", studentId, className, year, month);
        try {
            LocalDate startDate = LocalDate.of(year, month, 1);
            LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());

            return attendanceRepository.findByStudentIdAndClassNameAndDateRange(studentId, className, startDate, endDate);
        } catch (Exception e) {
            log.error("Error fetching attendance for student: {} in class: {}", studentId, className, e);
            throw new RuntimeException("Could not retrieve attendance records");
        }
    }
}