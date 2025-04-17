package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.repository.LeaveRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class LeaveService {
    @Autowired
    private LeaveRepository leaveRepository;

    public void applyLeave(Leave leave){
        leave.setAppliedDate(LocalDateTime.now());
        leaveRepository.save(leave);
    }

    public List<String> getLeavesByDateAndClass(String date, String className) {
        return leaveRepository.findByLeaveDateAndClassName(date, className);
    }

    @Transactional
    public void deleteLeave(String studentId, String leaveDate) {
        leaveRepository.deleteByStudentIdAndLeaveDate(studentId, leaveDate);
    }

    public List<Leave> getLeavesByStudentId(String studentId){
        return leaveRepository.findByStudentId(studentId);
    }

    public List<Leave> getAllLeaves() {
        return leaveRepository.findAll();
    }

    public List<Leave> getLeavesByClass(String className) {
        return leaveRepository.findByClassName(className);
    }

    public void deleteLeaveById(Long leaveId) {
        leaveRepository.deleteById(leaveId);
    }

    public Optional<Leave> getLeaveById(Long leaveId) {
        return leaveRepository.findById(leaveId);
    }
}
