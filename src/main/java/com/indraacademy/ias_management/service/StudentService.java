package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

@Service
public class StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentService.class);
    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StudentFeesService studentFeesService;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    private String getAcademicYear(LocalDate date) {
        Year currentYear = Year.of(date.getYear());
        if (date.getMonthValue() >= 4) {
            return currentYear.format(YEAR_FORMATTER) + "-" +
                    currentYear.plusYears(1).format(YEAR_FORMATTER);
        } else {
            return currentYear.minusYears(1).format(YEAR_FORMATTER) + "-" +
                    currentYear.format(YEAR_FORMATTER);
        }
    }

    @Transactional
    public Student addStudent(Student student) {
        if (student == null || student.getStudentId() == null || student.getStudentId().trim().isEmpty()) {
            log.error("Attempted to add student with null or empty Student object/ID.");
            throw new IllegalArgumentException("Student object and ID must be provided.");
        }
        log.info("Attempting to add new student with ID: {}", student.getStudentId());

        try {
            Optional<Student> existingStudent = studentRepository.findById(student.getStudentId());
            if (existingStudent.isPresent()) {
                log.warn("Student with ID {} already exists.", student.getStudentId());
                throw new IllegalArgumentException("Student with ID " + student.getStudentId() + " already exists.");
            }
            Student savedStudent = studentRepository.save(student);
            log.info("Successfully saved new student with ID: {}", savedStudent.getStudentId());

            String academicYear = getAcademicYear(LocalDate.now());

            // After successfully saving the student, create the default fees entries
            studentFeesService.createDefaultStudentFees(
                    savedStudent.getStudentId(),
                    savedStudent.getClassName(),
                    academicYear,
                    savedStudent.getTakesBus(),
                    savedStudent.getDistance()
            );
            log.info("Created default fee entries for student ID: {}", savedStudent.getStudentId());

            return savedStudent;
        } catch (DataAccessException e) {
            log.error("Data access error while adding student with ID: {}", student.getStudentId(), e);
            throw new RuntimeException("Failed to add student due to a database issue.", e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Re-throw specific business exceptions
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error while adding student with ID: {}", student.getStudentId(), e);
            throw new RuntimeException("An unexpected error occurred while adding the student.", e);
        }
    }

    public Optional<Student> getStudent(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to get student with null/empty ID.");
            return Optional.empty();
        }
        log.info("Fetching student with ID: {}", studentId);
        try {
            return studentRepository.findById(studentId);
        } catch (DataAccessException e) {
            log.error("Data access error fetching student with ID: {}", studentId, e);
            throw new RuntimeException("Failed to retrieve student data.", e);
        }
    }

    public List<Student> getActiveStudentsByClass(String className) {
        return studentRepository.findByClassNameAndStatus(className, StudentStatus.ACTIVE);
    }

    public List<Student> getUpcomingStudentsByClass(String className) {
        return studentRepository.findByClassNameAndStatus(className, StudentStatus.UPCOMING);
    }

    public List<Student> getInactiveStudentsByClass(String className) {
        return studentRepository.findByClassNameAndStatus(className, StudentStatus.INACTIVE);
    }

    private StudentStatus calculateStatus(LocalDate joiningDate, LocalDate leavingDate) {
        LocalDate today = LocalDate.now();

        if (joiningDate != null && joiningDate.isAfter(today)) {
            return StudentStatus.UPCOMING;
        }

        if (leavingDate != null && !leavingDate.isAfter(today)) {
            return StudentStatus.INACTIVE;
        }

        return StudentStatus.ACTIVE;
    }

    @Transactional
    public Student updateStudent(String studentId, Student updatedStudent, Integer effectiveFromMonth) {
        if (studentId == null || studentId.trim().isEmpty() || updatedStudent == null) {
            log.error("Invalid input for updateStudent: studentId or updatedStudent is null/empty.");
            throw new IllegalArgumentException("Student ID and updated student object must be provided.");
        }
        log.info("Attempting to update student with ID: {}", studentId);

        try {
            Optional<Student> existingStudentOptional = studentRepository.findById(studentId);
            if (existingStudentOptional.isEmpty()) {
                log.warn("Student with ID {} not found for update.", studentId);
                throw new NoSuchElementException("Student with ID " + studentId + " not found");
            }

            Student existingStudent = existingStudentOptional.get();

            // Comparison logic: uses Objects.equals for safe comparison
            boolean emailChanged = !Objects.equals(existingStudent.getEmail(), updatedStudent.getEmail());
            boolean classChanged = !Objects.equals(existingStudent.getClassName(), updatedStudent.getClassName());

            boolean busDetailsChanged = false;
            // Check 1: Bus status changed?
            if (!Objects.equals(existingStudent.getTakesBus(), updatedStudent.getTakesBus())) {
                busDetailsChanged = true;
            }
            // Check 2: Distance changed while bus is enabled?
            if (Boolean.TRUE.equals(updatedStudent.getTakesBus()) && !Objects.equals(existingStudent.getDistance(), updatedStudent.getDistance())) {
                busDetailsChanged = true;
            }

            if (emailChanged) {
                log.info("Email change detected for student {}. Updating user details.", studentId);
                Optional<User> userOptional = userDetailsService.findUserByUserId(studentId);
                userOptional.ifPresentOrElse(user -> {
                    user.setEmail(updatedStudent.getEmail());
                    userDetailsService.save(user);
                    log.info("Successfully updated user email for student ID: {}", studentId);
                }, () -> log.warn("User record not found for student ID {} when attempting to update email.", studentId));
            }

            if (classChanged) {
                log.info("Class change detected for student {}. Updating fee structure.", studentId);
                studentFeesService.updateStudentFeesForClassChange(studentId, updatedStudent.getClassName());
            }

            // Ensure the correct ID is set before saving the updated object
            updatedStudent.setStudentId(studentId);

            boolean joiningDateChanged = !Objects.equals(existingStudent.getJoiningDate(), updatedStudent.getJoiningDate());
            boolean leavingDateChanged = !Objects.equals(existingStudent.getLeavingDate(), updatedStudent.getLeavingDate());

            if (joiningDateChanged || leavingDateChanged) {
                StudentStatus newStatus = calculateStatus(
                        updatedStudent.getJoiningDate(),
                        updatedStudent.getLeavingDate()
                );
                updatedStudent.setStatus(newStatus);
                log.info("Status updated for student {} → {}", studentId, newStatus);
            }

            Student savedStudent = studentRepository.save(updatedStudent);
            log.info("Successfully saved updated student record for ID: {}", studentId);

            if (busDetailsChanged && updatedStudent.getTakesBus() != null) {
                log.info("Bus details changed for student {}. Updating bus fees from month: {}", studentId, effectiveFromMonth);
                studentFeesService.updateStudentBusFees(
                        studentId,
                        updatedStudent.getTakesBus(),
                        updatedStudent.getDistance(),
                        effectiveFromMonth
                );
            }

            return savedStudent;
        } catch (DataAccessException e) {
            log.error("Data access error while updating student with ID: {}", studentId, e);
            throw new RuntimeException("Failed to update student due to a database issue.", e);
        } catch (NoSuchElementException e) {
            // Re-throw specific business exceptions
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error while updating student with ID: {}", studentId, e);
            throw new RuntimeException("An unexpected error occurred while updating the student.", e);
        }
    }
}