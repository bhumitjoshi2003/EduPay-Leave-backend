package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

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
}
