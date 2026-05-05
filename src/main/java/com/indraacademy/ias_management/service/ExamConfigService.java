package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.ExamConfig;
import com.indraacademy.ias_management.entity.ExamSubjectEntry;
import com.indraacademy.ias_management.repository.ClassSubjectRepository;
import com.indraacademy.ias_management.repository.ExamConfigRepository;
import com.indraacademy.ias_management.repository.ExamSubjectEntryRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

@Service
public class ExamConfigService {

    private static final Logger log = LoggerFactory.getLogger(ExamConfigService.class);

    /** Classes whose subjects must match ClassSubject records before adding to an exam. */
    private static final Set<String> LOWER_CLASSES = Set.of(
            "1","2","3","4","5","6","7","8","9","10",
            "Play group","Nursery","KG","LKG","UKG"
    );

    @Autowired private ExamConfigRepository examConfigRepository;
    @Autowired private ExamSubjectEntryRepository examSubjectEntryRepository;
    @Autowired private ClassSubjectRepository classSubjectRepository;
    @Autowired private SecurityUtil securityUtil;

    // ─── ExamConfig ───────────────────────────────────────────────────────────

    @Cacheable(value = "exam-config", key = "@securityUtil.getSchoolId() + ':' + #session + '-' + #className")
    @Transactional(readOnly = true)
    public List<ExamConfig> getExams(String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        if (session != null && !session.isBlank() && className != null && !className.isBlank()) {
            return examConfigRepository.findBySessionAndClassNameAndSchoolId(session, className, schoolId);
        } else if (session != null && !session.isBlank()) {
            return examConfigRepository.findBySessionAndSchoolId(session, schoolId);
        } else if (className != null && !className.isBlank()) {
            return examConfigRepository.findByClassNameAndSchoolId(className, schoolId);
        }
        return examConfigRepository.findBySchoolId(schoolId);
    }

    @CacheEvict(value = "exam-config", allEntries = true)
    public ExamConfig addExam(String session, String className, String examName) {
        if (session == null || session.isBlank()
                || className == null || className.isBlank()
                || examName == null || examName.isBlank()) {
            throw new IllegalArgumentException("session, className, and examName are required.");
        }
        Long schoolId = securityUtil.getSchoolId();
        if (examConfigRepository.existsBySessionAndClassNameAndExamNameAndSchoolId(session, className, examName, schoolId)) {
            throw new IllegalArgumentException(
                    "Exam '" + examName + "' already exists for class " + className
                            + " in session " + session + ".");
        }
        ExamConfig config = new ExamConfig();
        config.setSession(session);
        config.setClassName(className);
        config.setExamName(examName);
        config.setSchoolId(schoolId);
        ExamConfig saved = examConfigRepository.save(config);
        log.info("Created ExamConfig id={} ({} / {} / {})", saved.getId(), session, className, examName);
        return saved;
    }

    @CacheEvict(value = "exam-config", allEntries = true)
    @Transactional
    public void deleteExam(Long id) {
        if (!examConfigRepository.existsById(id)) {
            throw new NoSuchElementException("ExamConfig not found: " + id);
        }
        examSubjectEntryRepository.deleteByExamConfigId(id);
        examConfigRepository.deleteById(id);
        log.info("Deleted ExamConfig id={} and its subject entries", id);
    }

    // ─── ExamSubjectEntry ─────────────────────────────────────────────────────

    @Cacheable(value = "exam-config", key = "@securityUtil.getSchoolId() + ':subjects-' + #examId")
    @Transactional(readOnly = true)
    public List<ExamSubjectEntry> getExamSubjects(Long examId) {
        Long schoolId = securityUtil.getSchoolId();
        ExamConfig exam = examConfigRepository.findById(examId)
                .orElseThrow(() -> new NoSuchElementException("ExamConfig not found: " + examId));
        if (!schoolId.equals(exam.getSchoolId())) {
            throw new SecurityException("Access denied: exam does not belong to your school.");
        }
        return examSubjectEntryRepository.findByExamConfigId(examId);
    }

    @CacheEvict(value = "exam-config", allEntries = true)
    public ExamSubjectEntry addExamSubject(Long examId, String subjectName,
                                           Integer maxMarks, LocalDate examDate) {
        Long schoolId = securityUtil.getSchoolId();
        ExamConfig exam = examConfigRepository.findById(examId)
                .orElseThrow(() -> new NoSuchElementException("ExamConfig not found: " + examId));
        if (!schoolId.equals(exam.getSchoolId())) {
            throw new SecurityException("Access denied: exam does not belong to your school.");
        }

        if (subjectName == null || subjectName.isBlank()) {
            throw new IllegalArgumentException("subjectName is required.");
        }
        if (maxMarks == null || maxMarks <= 0) {
            throw new IllegalArgumentException("maxMarks must be a positive integer.");
        }

        // For classes 1–10, validate the subject exists in ClassSubject configuration.
        if (LOWER_CLASSES.contains(exam.getClassName())
                && !classSubjectRepository.existsByClassNameAndSubjectNameAndSchoolId(exam.getClassName(), subjectName, securityUtil.getSchoolId())) {
            throw new IllegalArgumentException(
                    "Subject '" + subjectName + "' is not configured for class " + exam.getClassName()
                            + ". Add it via POST /api/subjects/class first.");
        }

        if (examSubjectEntryRepository.existsByExamConfigIdAndSubjectName(examId, subjectName)) {
            throw new IllegalArgumentException(
                    "Subject '" + subjectName + "' is already part of this exam.");
        }

        ExamSubjectEntry entry = new ExamSubjectEntry();
        entry.setExamConfigId(examId);
        entry.setSubjectName(subjectName);
        entry.setMaxMarks(maxMarks);
        entry.setExamDate(examDate);
        ExamSubjectEntry saved = examSubjectEntryRepository.save(entry);
        log.info("Added subject '{}' to ExamConfig id={}", subjectName, examId);
        return saved;
    }

    @CacheEvict(value = "exam-config", allEntries = true)
    public ExamSubjectEntry updateExamSubject(Long entryId, Integer maxMarks, LocalDate examDate) {
        Long schoolId = securityUtil.getSchoolId();
        ExamSubjectEntry entry = examSubjectEntryRepository.findById(entryId)
                .orElseThrow(() -> new NoSuchElementException("ExamSubjectEntry not found: " + entryId));
        if (!schoolId.equals(entry.getSchoolId())) {
            throw new SecurityException("Access denied: exam subject does not belong to your school.");
        }

        if (maxMarks != null) {
            if (maxMarks <= 0) throw new IllegalArgumentException("maxMarks must be positive.");
            entry.setMaxMarks(maxMarks);
        }
        if (examDate != null) {
            entry.setExamDate(examDate);
        }
        ExamSubjectEntry saved = examSubjectEntryRepository.save(entry);
        log.info("Updated ExamSubjectEntry id={}", entryId);
        return saved;
    }

    @CacheEvict(value = "exam-config", allEntries = true)
    @Transactional
    public void deleteExamSubject(Long entryId) {
        Long schoolId = securityUtil.getSchoolId();
        ExamSubjectEntry entry = examSubjectEntryRepository.findById(entryId)
                .orElseThrow(() -> new NoSuchElementException("ExamSubjectEntry not found: " + entryId));
        if (!schoolId.equals(entry.getSchoolId())) {
            throw new SecurityException("Access denied: exam subject does not belong to your school.");
        }
        examSubjectEntryRepository.deleteById(entryId);
        log.info("Deleted ExamSubjectEntry id={}", entryId);
    }

    /** Resolves an ExamSubjectEntry to its parent ExamConfig's className. */
    public Optional<String> resolveClassName(Long examSubjectEntryId) {
        return examSubjectEntryRepository.findById(examSubjectEntryId)
                .flatMap(e -> examConfigRepository.findById(e.getExamConfigId()))
                .map(ExamConfig::getClassName);
    }

    /** Resolves an ExamConfig's className. */
    public Optional<String> resolveClassNameForExam(Long examConfigId) {
        return examConfigRepository.findById(examConfigId).map(ExamConfig::getClassName);
    }
}
