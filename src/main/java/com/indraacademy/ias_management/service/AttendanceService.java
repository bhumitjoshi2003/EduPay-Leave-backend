package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AttendanceService {

    @Autowired
    private AttendanceRepository attendanceRepository;

    public void saveAttendance(List<Attendance> attendanceList) {
        attendanceRepository.deleteByAbsentDateAndClassName(attendanceList.getFirst().getAbsentDate(), attendanceList.getFirst().getClassName());
        attendanceRepository.saveAll(attendanceList);
    }

    public List<Attendance> getAttendanceByDateAndClass(LocalDate absentDate, String className) {
        return attendanceRepository.findByAbsentDateAndClassName(absentDate, className);
    }

    public Map<String, Long> getAttendanceCounts(String studentId, int year, int month) {
        long studentAbsentCount = attendanceRepository.countAbsences(studentId, year, month);
        long totalAbsentCount = attendanceRepository.countAbsences("X", year, month);

        Map<String, Long> counts = new HashMap<>();
        counts.put("studentAbsent", studentAbsentCount);
        counts.put("totalAbsent", totalAbsentCount);
        return counts;
    }

    public long getTotalUnappliedLeaveCount(String studentId, String session) {
        String[] years = session.split("-");
        int startYear = Integer.parseInt(years[0]);
        int endYear = Integer.parseInt(years[1]);
        return attendanceRepository.countUnappliedLeavesForAcademicYear(studentId, startYear, endYear);
    }
}
