package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class StudentService {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StudentFeesService studentFeesService;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Transactional
    public Student addStudent(Student student) {
        Optional<Student> existingStudent = studentRepository.findById(student.getStudentId());
        if (existingStudent.isPresent()) {
            throw new IllegalArgumentException("Student with ID " + student.getStudentId() + " already exists.");
        }
        Student savedStudent = studentRepository.save(student);

        // After successfully saving the student, create the default fees entries
        String academicYear = Year.now().format(DateTimeFormatter.ofPattern("yyyy")) + "-" +
                Year.now().plusYears(1).format(DateTimeFormatter.ofPattern("yyyy"));
        studentFeesService.createDefaultStudentFees(
                savedStudent.getStudentId(),
                savedStudent.getClassName(),
                academicYear,
                savedStudent.getTakesBus(),
                savedStudent.getDistance()
        );
        return savedStudent;
    }

    public Optional<Student> getStudent(String studentId) {
        return studentRepository.findById(studentId);
    }

    @Transactional
    public Student updateStudent(String studentId, Student updatedStudent, Integer effectiveFromMonth) {
        Optional<Student> existingStudentOptional = studentRepository.findById(studentId);
        if (existingStudentOptional.isPresent()) {
            Student existingStudent = existingStudentOptional.get();
            boolean emailChanged = !existingStudent.getEmail().equals(updatedStudent.getEmail());
            boolean classChanged = !existingStudent.getClassName().equals(updatedStudent.getClassName());
            boolean busDetailsChanged = updatedStudent.getTakesBus() != null && !existingStudent.getTakesBus().equals(updatedStudent.getTakesBus());

            if (updatedStudent.getTakesBus() != null && updatedStudent.getTakesBus() && updatedStudent.getDistance() != null && !existingStudent.getDistance().equals(updatedStudent.getDistance())) {
                busDetailsChanged = true;
            }

            if (emailChanged) {
                Optional<User> userOptional = userDetailsService.findUserByUserId(studentId);
                userOptional.ifPresent(user -> {
                    user.setEmail(updatedStudent.getEmail());
                    userDetailsService.save(user);
                });
            }
            if (classChanged) {
                studentFeesService.updateStudentFeesForClassChange(studentId, updatedStudent.getClassName());
            }

            updatedStudent.setStudentId(studentId);
            Student savedStudent = studentRepository.save(updatedStudent);

            if (busDetailsChanged && updatedStudent.getTakesBus() != null) {
                studentFeesService.updateStudentBusFees(
                        studentId,
                        updatedStudent.getTakesBus(),
                        updatedStudent.getDistance(),
                        effectiveFromMonth
                );
            }

            return savedStudent;
        } else {
            throw new NoSuchElementException("Student with ID " + studentId + " not found");
        }
    }
}