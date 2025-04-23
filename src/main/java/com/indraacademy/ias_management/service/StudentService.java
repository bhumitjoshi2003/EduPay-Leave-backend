package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class StudentService {

    @Autowired
    private StudentRepository studentRepository;

    public Student addStudent(Student student) {
        Optional<Student> existingStudent = studentRepository.findById(student.getStudentId());
        if (existingStudent.isPresent()) {
            throw new IllegalArgumentException("Student with ID " + student.getStudentId() + " already exists.");
        }
        return studentRepository.save(student);
    }

    public Optional<Student> getStudent(String studentId) {
        return studentRepository.findById(studentId);
    }

    public Student updateStudent(Student student) {
        return studentRepository.save(student);
    }
}