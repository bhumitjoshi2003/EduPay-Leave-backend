package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.repository.LeaveRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
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

    @Transactional
    public void deleteLeave(String studentId, String leaveDate) {
        leaveRepository.deleteByStudentIdAndLeaveDate(studentId, leaveDate);
    }

    @Transactional
    public void deleteLeaveById(String leaveId) {
        leaveRepository.deleteById(leaveId);
    }

    public Optional<Leave> getLeaveById(String leaveId) {
        return leaveRepository.findById(leaveId);
    }

    public Page<Leave> getLeavesFiltered(String className, String studentId, String date, Pageable pageable) {
        if (className != null && studentId != null && date != null) {
            return leaveRepository.findByClassNameAndStudentIdContainingAndLeaveDate(className, studentId, date, pageable);
        } else if (className != null && studentId != null) {
            return leaveRepository.findByClassNameAndStudentIdContaining(className, studentId, pageable);
        } else if (className != null && date != null) {
            return leaveRepository.findByClassNameAndLeaveDate(className, date, pageable);
        } else if (studentId != null && date != null) {
            return leaveRepository.findByStudentIdContainingAndLeaveDate(studentId, date, pageable);
        } else if (className != null) {
            return leaveRepository.findByClassName(className, pageable);
        } else if (studentId != null) {
            return leaveRepository.findByStudentIdContaining(studentId, pageable);
        } else if (date != null) {
            return leaveRepository.findByLeaveDate(date, pageable);
        } else {
            return leaveRepository.findAll(pageable);
        }
    }

    public Page<Leave> getLeavesByStudentId(String studentId, Pageable pageable) {
        return leaveRepository.findByStudentId(studentId, pageable);
    }

    public List<String> getLeavesByDateAndClass(String date, String className) {
        return leaveRepository.findByLeaveDateAndClassName(date, className);
    }
}