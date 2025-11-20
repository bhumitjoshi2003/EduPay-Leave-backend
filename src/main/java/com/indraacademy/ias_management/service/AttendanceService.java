package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Transactional
    public void saveAttendance(List<Attendance> attendanceList) {
        if (attendanceList == null || attendanceList.isEmpty()) {
            log.warn("Attempted to save empty or null attendance list.");
            return;
        }

        LocalDate absentDate = attendanceList.getFirst().getDate();
        String className = attendanceList.getFirst().getClassName();
        log.info("Saving attendance for date: {} and class: {}. List size: {}", absentDate, className, attendanceList.size());

        try {
            log.debug("Deleting existing attendance records for date: {} and class: {}", absentDate, className);
            attendanceRepository.deleteByDateAndClassName(absentDate, className);

            log.debug("Saving new attendance records.");
            attendanceRepository.saveAll(attendanceList);
            log.info("Successfully saved {} attendance records for date: {} and class: {}", attendanceList.size(), absentDate, className);
        } catch (DataAccessException e) {
            log.error("Data access error during saveAttendance for date {} and class {}", absentDate, className, e);
            throw new RuntimeException("Could not save attendance due to data access issue", e);
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
        try {
            Optional<Student> studentOptional = studentRepository.findById(studentId);
            if (studentOptional.isEmpty()) {
                log.warn("Student not found with ID: {}", studentId);
                return counts;
            }
            student = studentOptional.get();
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
            totalWorkingDays   = attendanceRepository.countAbsences("X", year, month);
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
                    long daysBeforeJoin = attendanceRepository.countAbsencesBeforeJoin("X", year, month, joinDate);
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
                    long daysAfterLeave = attendanceRepository.countAbsencesAfterLeave("X", year, month, leaveDate);
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
    public void updateChargePaidAfterPayment(String studentId, String session) {
        if (studentId == null || studentId.trim().isEmpty() || session == null || session.trim().isEmpty()) {
            log.warn("Attempted to update charge paid with null/empty student ID or session.");
            return;
        }
        log.info("Updating chargePaid status for student ID: {} and session: {}", studentId, session);

        try {
            String[] years = session.split("-");
            if (years.length != 2) {
                log.error("Invalid session format: {}. Cannot update charge paid status.", session);
                return;
            }

            int startYear = Integer.parseInt(years[0]);
            int endYear = Integer.parseInt(years[1]);
            LocalDate startDate = LocalDate.of(startYear, 4, 1);
            LocalDate endDate = LocalDate.of(endYear, 3, 31);

            attendanceRepository.updateChargePaidForSession(studentId, startDate, endDate);
            log.info("Successfully updated chargePaid status records for student ID: {}", studentId);
        } catch (NumberFormatException e) {
            log.error("Error parsing session year for session: {}. Cannot update charge paid status.", session, e);
        } catch (DataAccessException e) {
            log.error("Data access error updating chargePaid for student ID: {} and session: {}", studentId, session, e);
            throw new RuntimeException("Could not update charge paid status due to data access issue", e);
        }
    }

    public void deleteAttendanceByDateAndClass(LocalDate date, String className) {
        if (date == null || className == null || className.trim().isEmpty()) {
            log.warn("Attempted to delete attendance with null date or empty class name.");
            return;
        }
        log.info("Deleting attendance for date: {} and class: {}", date, className);
        try {
            attendanceRepository.deleteByDateAndClassName(date, className);
            log.info("Successfully deleted attendance records for date: {} and class: {}", date, className);
        } catch (DataAccessException e) {
            log.error("Data access error deleting attendance for date {} and class {}", date, className, e);
            throw new RuntimeException("Could not delete attendance due to data access issue", e);
        }
    }
}