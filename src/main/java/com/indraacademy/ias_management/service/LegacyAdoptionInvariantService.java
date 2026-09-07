package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.InvariantSnapshot;
import com.indraacademy.ias_management.entity.ClassTeacherActivation;
import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.ClassTeacherActivationRepository;
import com.indraacademy.ias_management.repository.ClassTeacherResponsibilityRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * Phase F5A: a purely read-only, content-based fingerprint of everything a legacy adoption run
 * could possibly touch (timetable rows, the live Teacher projection) or must never touch
 * (class_teacher_responsibility, activation provenance) for one school/session. Capturing this
 * before and after a dry run and asserting equality is what actually PROVES non-mutation — not
 * "the code path says dryRun=true" and not a bare row count, which could stay the same while
 * content silently changed. Same SHA-256-of-canonical-sorted-tuples technique as
 * {@code ClassTeacherActivationService}'s configuration fingerprint, for the same reason.
 */
@Service
public class LegacyAdoptionInvariantService {

    @Autowired private TimetableRepository timetableRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ClassTeacherResponsibilityRepository responsibilityRepository;
    @Autowired private ClassTeacherActivationRepository activationRepository;

    @Transactional(readOnly = true)
    public InvariantSnapshot capture(Long schoolId, Long academicSessionId) {
        List<TimetableEntry> allTimetable = timetableRepository.findBySchoolId(schoolId);
        long nullSessionCount = allTimetable.stream().filter(e -> e.getAcademicSessionId() == null).count();
        String timetableChecksum = fingerprint(allTimetable.stream()
                .map(e -> e.getId() + ":" + e.getAcademicSessionId() + ":" + e.getClassId() + ":" + e.getSectionId()
                        + ":" + e.getClassName() + ":" + e.getSectionName() + ":" + e.getDay() + ":" + e.getPeriodNumber()
                        + ":" + e.getStartTime() + ":" + e.getEndTime() + ":" + e.getSubjectName() + ":"
                        + e.getTeacherId() + ":" + e.getTeacherName() + ":" + e.getSimultaneousGroup())
                .toList());

        List<Teacher> liveAssignments = teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(schoolId);
        String teacherChecksum = fingerprint(liveAssignments.stream()
                .map(t -> t.getTeacherId() + ":" + t.getClassTeacher() + ":" + t.getClassTeacherSectionId())
                .toList());

        List<ClassTeacherResponsibility> responsibilities =
                responsibilityRepository.findByAcademicSessionIdAndSchoolId(academicSessionId, schoolId);
        String responsibilityChecksum = fingerprint(responsibilities.stream()
                .map(r -> r.getClassId() + ":" + r.getSectionId() + ":" + r.getTeacherId())
                .toList());

        Optional<ClassTeacherActivation> activation =
                activationRepository.findBySchoolIdAndAcademicSessionId(schoolId, academicSessionId);
        String activationChecksum = fingerprint(activation.stream()
                .map(a -> a.getConfigurationFingerprint() + ":" + a.getAppliedAt() + ":" + a.getAppliedBy())
                .toList());

        return new InvariantSnapshot(
                schoolId, academicSessionId,
                allTimetable.size(), nullSessionCount, timetableChecksum,
                liveAssignments.size(), teacherChecksum,
                responsibilities.size(), responsibilityChecksum,
                activation.isPresent() ? 1 : 0, activationChecksum);
    }

    private String fingerprint(List<String> parts) {
        String canonical = String.join("|", parts.stream().sorted().toList());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
