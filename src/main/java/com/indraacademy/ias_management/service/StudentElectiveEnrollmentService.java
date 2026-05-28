package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ElectiveEnrollmentDTO;
import com.indraacademy.ias_management.entity.ClassSubject;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentElectiveEnrollment;
import com.indraacademy.ias_management.repository.ClassSubjectRepository;
import com.indraacademy.ias_management.repository.StudentElectiveEnrollmentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StudentElectiveEnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(StudentElectiveEnrollmentService.class);

    @Autowired private StudentElectiveEnrollmentRepository repo;
    @Autowired private ClassSubjectRepository classSubjectRepository;
    @Autowired private StudentService studentService;
    @Autowired private SecurityUtil securityUtil;

    /**
     * Returns all elective enrollments for a class, enriched with student names.
     */
    @Transactional(readOnly = true)
    public List<ElectiveEnrollmentDTO> getEnrollmentsForClass(String className) {
        Long schoolId = securityUtil.getSchoolId();
        List<StudentElectiveEnrollment> enrollments = repo.findByClassNameAndSchoolId(className, schoolId);

        // Batch load student names
        Set<String> studentIds = enrollments.stream()
                .map(StudentElectiveEnrollment::getStudentId).collect(Collectors.toSet());
        Map<String, String> nameById = studentService.getActiveStudentsByClass(className).stream()
                .filter(s -> studentIds.contains(s.getStudentId()))
                .collect(Collectors.toMap(Student::getStudentId, Student::getName));

        return enrollments.stream()
                .map(e -> new ElectiveEnrollmentDTO(
                        e.getId(), e.getStudentId(),
                        nameById.getOrDefault(e.getStudentId(), e.getStudentId()),
                        e.getClassName(), e.getOptionalGroup(), e.getSubjectName()))
                .collect(Collectors.toList());
    }

    /**
     * Enroll (or update) a student's elective choice for a given optional group.
     * If the student already has a choice for this group, the old one is replaced.
     */
    @Transactional
    public ElectiveEnrollmentDTO enroll(String studentId, String className,
                                         String optionalGroup, String subjectName) {
        Long schoolId = securityUtil.getSchoolId();

        // Validate the subject is a real optional subject in this class/group
        List<ClassSubject> electives = classSubjectRepository
                .findByClassNameAndOptionalTrueAndSchoolId(className, schoolId);
        boolean valid = electives.stream().anyMatch(cs ->
                cs.getOptionalGroup() != null
                        && cs.getOptionalGroup().equals(optionalGroup)
                        && cs.getSubjectName().equals(subjectName));
        if (!valid) {
            throw new IllegalArgumentException(
                    "Subject '" + subjectName + "' is not a valid elective in group '" + optionalGroup
                            + "' for class " + className);
        }

        // Delete existing enrollment for this group (upsert)
        repo.deleteByStudentIdAndClassNameAndOptionalGroupAndSchoolId(studentId, className, optionalGroup, schoolId);

        StudentElectiveEnrollment e = new StudentElectiveEnrollment();
        e.setSchoolId(schoolId);
        e.setStudentId(studentId);
        e.setClassName(className);
        e.setOptionalGroup(optionalGroup);
        e.setSubjectName(subjectName);
        StudentElectiveEnrollment saved = repo.save(e);

        String name = studentService.getStudent(studentId)
                .map(Student::getName).orElse(studentId);
        log.info("Enrolled student {} in elective group='{}' subject='{}' class={}", studentId, optionalGroup, subjectName, className);
        return new ElectiveEnrollmentDTO(saved.getId(), studentId, name, className, optionalGroup, subjectName);
    }

    /**
     * Remove a student's elective enrollment for a specific group.
     */
    @Transactional
    public void unenroll(String studentId, String className, String optionalGroup) {
        Long schoolId = securityUtil.getSchoolId();
        repo.deleteByStudentIdAndClassNameAndOptionalGroupAndSchoolId(studentId, className, optionalGroup, schoolId);
        log.info("Unenrolled student {} from elective group='{}' class={}", studentId, optionalGroup, className);
    }

    /**
     * Bulk-assign: set the same elective choice for a list of students.
     * Existing choices for the given group are replaced.
     */
    @Transactional
    public int bulkEnroll(List<String> studentIds, String className,
                          String optionalGroup, String subjectName) {
        int count = 0;
        for (String sid : studentIds) {
            try {
                enroll(sid, className, optionalGroup, subjectName);
                count++;
            } catch (IllegalArgumentException ex) {
                log.warn("Bulk enroll skipped studentId={}: {}", sid, ex.getMessage());
            }
        }
        return count;
    }
}
