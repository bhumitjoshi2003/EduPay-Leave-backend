package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AttendanceService {

    @Autowired private AttendanceRepository attendanceRepository;

    @Autowired private StudentRepository studentRepository;

    public void saveAttendance(List<Attendance> attendanceList) {
        attendanceRepository.deleteByAbsentDateAndClassName(attendanceList.getFirst().getAbsentDate(), attendanceList.getFirst().getClassName());
        attendanceRepository.saveAll(attendanceList);
    }

    public List<Attendance> getAttendanceByDateAndClass(LocalDate absentDate, String className) {
        return attendanceRepository.findByAbsentDateAndClassName(absentDate, className);
    }

    public Map<String, Long> getAttendanceCounts(String studentId, int year, int month) {
        Student student = studentRepository.findById(studentId).orElse(null);

        Map<String, Long> counts = new HashMap<>();

        if (student == null) {
            counts.put("studentAbsent", 0L);
            counts.put("totalAbsent", 0L);
            return counts;
        }

        LocalDate studentJoiningDate = student.getJoiningDate();

        long studentAbsentCount = attendanceRepository.countAbsences(studentId, year, month);
        long totalWorkingDays = attendanceRepository.countAbsences("X", year, month);

        if (studentJoiningDate == null || studentJoiningDate.getYear() > year || (studentJoiningDate.getYear() == year && studentJoiningDate.getMonthValue() >  month)) {
            studentAbsentCount = 0L;
            totalWorkingDays = 0L;
        } else if (studentJoiningDate.getYear() == year && studentJoiningDate.getMonthValue() == month) {
            LocalDate joinDate = studentJoiningDate;
            totalWorkingDays -= attendanceRepository.countAbsencesBeforeJoin("X", year, month, joinDate);
        }

        counts.put("studentAbsent", studentAbsentCount);
        counts.put("totalAbsent", totalWorkingDays);

        return counts;
    }

    public LocalDate getStudentJoinDate(String studentId) {
        Student student = studentRepository.findById(studentId).orElse(null); //  method in StudentRepository
        return (student != null) ? student.getJoiningDate() : null;
    }

    public long getTotalUnappliedLeaveCount(String studentId, String session) {
        String[] years = session.split("-");
        int startYear = Integer.parseInt(years[0]);
        int endYear = Integer.parseInt(years[1]);
        return attendanceRepository.countUnappliedLeavesForAcademicYear(studentId, startYear, endYear);
    }

    @Transactional
    public void updateChargePaidAfterPayment(String studentId, String session) {
        String[] years = session.split("-");
        int startYear = Integer.parseInt(years[0]);
        int endYear = Integer.parseInt(years[1]);
        LocalDate startDate = LocalDate.of(startYear, 4, 1);
        LocalDate endDate = LocalDate.of(endYear, 3, 31);
        attendanceRepository.updateChargePaidForSession(studentId, startDate, endDate);
    }
}
