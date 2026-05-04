package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.StudentStreamDTO;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class StudentStreamService {

    private static final Logger log = LoggerFactory.getLogger(StudentStreamService.class);

    @Autowired private StudentStreamSelectionRepository selectionRepository;
    @Autowired private AcademicStreamRepository streamRepository;
    @Autowired private OptionalSubjectRepository optionalSubjectRepository;
    @Autowired private StudentService studentService;
    @Autowired private SecurityUtil securityUtil;

    @Transactional(readOnly = true)
    public Optional<StudentStreamSelection> getSelection(String studentId) {
        return selectionRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId());
    }

    @Transactional
    public StudentStreamSelection save(String studentId, Long streamId, Long optionalSubjectId) {
        validateIds(studentId, streamId, optionalSubjectId);
        Long schoolId = securityUtil.getSchoolId();

        if (selectionRepository.findByStudentIdAndSchoolId(studentId, schoolId).isPresent()) {
            throw new IllegalArgumentException(
                    "Stream already assigned to student " + studentId + ". Use PUT to update.");
        }

        StudentStreamSelection sel = new StudentStreamSelection();
        sel.setStudentId(studentId);
        sel.setStreamId(streamId);
        sel.setOptionalSubjectId(optionalSubjectId);
        sel.setSchoolId(schoolId);
        StudentStreamSelection saved = selectionRepository.save(sel);
        log.info("Assigned stream {} to student {}", streamId, studentId);
        return saved;
    }

    @Transactional
    public StudentStreamSelection update(String studentId, Long streamId, Long optionalSubjectId) {
        validateIds(studentId, streamId, optionalSubjectId);

        StudentStreamSelection sel = selectionRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException(
                        "No stream selection found for student " + studentId));

        sel.setStreamId(streamId);
        sel.setOptionalSubjectId(optionalSubjectId);
        StudentStreamSelection saved = selectionRepository.save(sel);
        log.info("Updated stream selection for student {}", studentId);
        return saved;
    }

    @Transactional
    public void delete(String studentId) {
        Long schoolId = securityUtil.getSchoolId();
        if (selectionRepository.findByStudentIdAndSchoolId(studentId, schoolId).isEmpty()) {
            throw new NoSuchElementException("No stream selection found for student " + studentId);
        }
        selectionRepository.deleteByStudentIdAndSchoolId(studentId, schoolId);
        log.info("Deleted stream selection for student {}", studentId);
    }

    /**
     * Returns all active students in a class with their stream selections.
     * Students without a selection still appear with null stream fields.
     */
    @Transactional(readOnly = true)
    public List<StudentStreamDTO> getClassSelections(String className) {
        List<Student> students = studentService.getActiveStudentsByClass(className);

        Long schoolId = securityUtil.getSchoolId();
        return students.stream().map(student -> {
            Optional<StudentStreamSelection> sel = selectionRepository.findByStudentIdAndSchoolId(student.getStudentId(), schoolId);

            if (sel.isEmpty()) {
                return new StudentStreamDTO(student.getStudentId(), student.getName(),
                        null, null, null, null);
            }

            StudentStreamSelection s = sel.get();
            String streamName = streamRepository.findById(s.getStreamId())
                    .map(AcademicStream::getStreamName).orElse(null);
            String optSubjectName = null;
            if (s.getOptionalSubjectId() != null) {
                optSubjectName = optionalSubjectRepository.findById(s.getOptionalSubjectId())
                        .map(OptionalSubject::getSubjectName).orElse(null);
            }

            return new StudentStreamDTO(student.getStudentId(), student.getName(),
                    s.getStreamId(), streamName, s.getOptionalSubjectId(), optSubjectName);
        }).collect(Collectors.toList());
    }

    private void validateIds(String studentId, Long streamId, Long optionalSubjectId) {
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("studentId is required.");
        }
        if (!streamRepository.existsById(streamId)) {
            throw new NoSuchElementException("Stream not found: " + streamId);
        }
        if (optionalSubjectId != null && !optionalSubjectRepository.existsById(optionalSubjectId)) {
            throw new NoSuchElementException("OptionalSubject not found: " + optionalSubjectId);
        }
    }
}
