package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class StudentFeesService {

    @Autowired
    private StudentFeesRepository studentFeesRepository;

    public List<StudentFees> getStudentFees(String studentId, String year) {
        return studentFeesRepository.findByStudentIdAndYear(studentId, year);
    }

    public StudentFees updateStudentFees(StudentFees studentFees) {
        return studentFeesRepository.save(studentFees);
    }

    public Optional<StudentFees> getStudentFee(String studentId, String year, Integer month) {
        return Optional.ofNullable(studentFeesRepository.findByStudentIdAndYearAndMonth(studentId, year, month));
    }

    public StudentFees createStudentFees(StudentFees studentFees) {
        return studentFeesRepository.save(studentFees);
    }

    public List<String> getDistinctYearsByStudentId(String studentId) {
        return studentFeesRepository.findDistinctYearsByStudentId(studentId);
    }
}