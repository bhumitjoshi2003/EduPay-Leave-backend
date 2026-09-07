package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.AdoptionOutcome;
import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.ResponsibilityCandidate;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.repository.ClassTeacherResponsibilityRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase F5A: classifies every LIVE {@code Teacher.classTeacher}/{@code classTeacherSectionId}
 * assignment against one explicit target {@link AcademicSession}'s
 * {@code class_teacher_responsibility} configuration, and — only via {@link #classifyAndApply} —
 * inserts a new responsibility row for every assignment classified {@code SAFE}.
 *
 * <p>This adoption NEVER writes {@code Teacher.classTeacher}/{@code classTeacherSectionId} (the
 * live projection is read-only input here, never a write target) and NEVER calls
 * {@link ClassTeacherActivationService} — bridging configuration back to the live projection
 * remains a separate, explicit, later action, exactly as it already is for any other
 * responsibility row.
 *
 * <p>A teacher can hold at most one {@code classTeacher} value by construction (one Teacher row,
 * one pair of fields) — the "one teacher, multiple incompatible live assignments" scenario the
 * product spec asks about cannot occur under the current schema, so no code models it. What CAN
 * occur, and is checked here, is two DIFFERENT teachers both pointing at the same class/section —
 * nothing prevents that at the entity level today.
 */
@Service
public class LegacyResponsibilityAdoptionWorker {

    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private ClassTeacherResponsibilityRepository responsibilityRepository;
    @Autowired private TimetableSessionAccessService sessionAccess;

    @Transactional(readOnly = true)
    public ClassificationResult classify(Long schoolId, Long academicSessionId) {
        AcademicSession target = sessionAccess.requireOwnedSession(schoolId, academicSessionId);
        return classifyInternal(schoolId, target, false);
    }

    @Transactional
    public ClassificationResult classifyAndApply(Long schoolId, Long academicSessionId) {
        AcademicSession target = sessionAccess.lockWritableOwnedSession(schoolId, academicSessionId);
        return classifyInternal(schoolId, target, true);
    }

    private ClassificationResult classifyInternal(Long schoolId, AcademicSession target, boolean write) {
        Map<String, SchoolClass> classesByName = new HashMap<>();
        for (SchoolClass c : schoolClassRepository.findBySchoolIdOrderByDisplayOrderAsc(schoolId)) {
            classesByName.putIfAbsent(c.getName(), c);
        }
        Map<Long, List<Section>> sectionsByClassId = new HashMap<>();
        for (Section s : sectionRepository.findBySchoolIdOrderByClassIdAscDisplayOrderAsc(schoolId)) {
            if (s.isActive()) {
                sectionsByClassId.computeIfAbsent(s.getClassId(), k -> new ArrayList<>()).add(s);
            }
        }
        Map<String, ClassTeacherResponsibility> existingByKey = new HashMap<>();
        for (ClassTeacherResponsibility r : responsibilityRepository.findByAcademicSessionIdAndSchoolId(target.getId(), schoolId)) {
            existingByKey.put(key(r.getClassId(), r.getSectionId()), r);
        }

        List<Teacher> liveAssignments = new ArrayList<>(teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(schoolId));
        liveAssignments.sort((a, b) -> a.getTeacherId().compareTo(b.getTeacherId()));

        Counters counters = new Counters();
        List<Resolved> resolved = new ArrayList<>(liveAssignments.size());
        List<ResponsibilityCandidate> details = new ArrayList<>(liveAssignments.size());

        // Pass 1: resolve class/section/teacher-eligibility for every live assignment.
        for (Teacher teacher : liveAssignments) {
            counters.scanned++;
            resolved.add(resolveOne(teacher, classesByName, sectionsByClassId));
        }

        // Pass 2: detect two different teachers claiming the same class/section.
        Map<String, List<Resolved>> byTargetKey = new HashMap<>();
        for (Resolved r : resolved) {
            if (r.classification.outcome == null) {
                byTargetKey.computeIfAbsent(key(r.classification.resolvedClassId, r.classification.resolvedSectionId),
                        k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<String, List<Resolved>> e : byTargetKey.entrySet()) {
            if (e.getValue().size() > 1) {
                String others = e.getValue().stream().map(r -> r.teacher.getTeacherId())
                        .reduce((a, b) -> a + ", " + b).orElse("");
                for (Resolved r : e.getValue()) {
                    r.classification = Classification.invalid(
                            "Multiple teachers (" + others + ") are live class-teachers of the same class/section "
                                    + "— cannot adopt without an admin resolving which is authoritative.");
                }
            }
        }

        // Pass 3: compare surviving candidates against the target session's existing configuration.
        for (Resolved r : resolved) {
            Classification c = r.classification;
            if (c.outcome == null) {
                ClassTeacherResponsibility existing = existingByKey.get(key(c.resolvedClassId, c.resolvedSectionId));
                if (existing == null) {
                    c = Classification.safe(c.resolvedClassId, c.resolvedSectionId);
                } else if (Objects.equals(existing.getTeacherId(), r.teacher.getTeacherId())) {
                    c = Classification.alreadyAdopted(c.resolvedClassId, c.resolvedSectionId);
                } else {
                    c = Classification.invalid(
                            "An existing responsibility for this class/section already names a different teacher ('"
                                    + existing.getTeacherId() + "'); never overwritten.");
                }
            }

            switch (c.outcome) {
                case SAFE -> {
                    counters.safe++;
                    if (write) {
                        ClassTeacherResponsibility row = new ClassTeacherResponsibility();
                        row.setSchoolId(schoolId);
                        row.setAcademicSessionId(target.getId());
                        row.setClassId(c.resolvedClassId);
                        row.setSectionId(c.resolvedSectionId);
                        row.setTeacherId(r.teacher.getTeacherId());
                        responsibilityRepository.save(row);
                        counters.adopted++;
                    }
                }
                case REQUIRES_ADMIN_CONFIRMATION -> counters.requiresAdminConfirmation++;
                case INVALID_OR_CONFLICTING -> counters.invalidOrConflicting++;
                case ALREADY_ADOPTED -> counters.alreadyAdopted++;
                case SKIPPED -> { /* never produced by this worker */ }
            }

            details.add(new ResponsibilityCandidate(
                    r.teacher.getTeacherId(), r.teacher.getName(), r.teacher.getClassTeacher(),
                    r.teacher.getClassTeacherSectionId(), c.outcome, c.reason,
                    c.resolvedClassId, c.resolvedSectionId));
        }

        return new ClassificationResult(counters, details);
    }

    private Resolved resolveOne(Teacher teacher, Map<String, SchoolClass> classesByName,
            Map<Long, List<Section>> sectionsByClassId) {
        if (teacher.getStatus() != TeacherStatus.ACTIVE) {
            return new Resolved(teacher, Classification.invalid(
                    "Live class-teacher '" + teacher.getTeacherId() + "' is not ACTIVE (status="
                            + teacher.getStatus() + ") — stale live data cannot be adopted as a responsibility."));
        }

        SchoolClass schoolClass = classesByName.get(teacher.getClassTeacher());
        if (schoolClass == null) {
            return new Resolved(teacher, Classification.invalid(
                    "Unknown class: live classTeacher value '" + teacher.getClassTeacher()
                            + "' does not match any SchoolClass in this school."));
        }

        List<Section> sectionsForClass = sectionsByClassId.getOrDefault(schoolClass.getId(), List.of());
        boolean hasSections = !sectionsForClass.isEmpty();
        Long legacySectionId = teacher.getClassTeacherSectionId();

        if (!hasSections) {
            if (legacySectionId != null) {
                return new Resolved(teacher, Classification.invalid(
                        "Class '" + teacher.getClassTeacher() + "' has no configured sections, but "
                                + "classTeacherSectionId=" + legacySectionId + " is set — data anomaly."));
            }
            return new Resolved(teacher, Classification.safeCandidate(schoolClass.getId(), null));
        }

        if (legacySectionId == null) {
            return new Resolved(teacher, Classification.requiresConfirmation(
                    "Class '" + teacher.getClassTeacher() + "' has sections, but this teacher's "
                            + "classTeacherSectionId was never resolved — an admin must pick a section before "
                            + "this can become a session-scoped responsibility.",
                    schoolClass.getId(), null));
        }

        Section matched = sectionsForClass.stream().filter(s -> s.getId().equals(legacySectionId)).findFirst().orElse(null);
        if (matched == null) {
            return new Resolved(teacher, Classification.invalid(
                    "classTeacherSectionId=" + legacySectionId + " does not belong to class '"
                            + teacher.getClassTeacher() + "' (or does not exist)."));
        }

        return new Resolved(teacher, Classification.safeCandidate(schoolClass.getId(), matched.getId()));
    }

    private String key(Long classId, Long sectionId) {
        return classId + "|" + (sectionId == null ? "-" : sectionId);
    }

    private static final class Resolved {
        final Teacher teacher;
        Classification classification;
        Resolved(Teacher teacher, Classification classification) {
            this.teacher = teacher;
            this.classification = classification;
        }
    }

    private static final class Classification {
        /** Null means "class/section/teacher-eligibility resolved cleanly, but not yet compared
         *  against duplicate live assignments or the target session's existing configuration"
         *  (passes 2 and 3 finalize it) — never returned in the final report as null. */
        final AdoptionOutcome outcome;
        final String reason;
        final Long resolvedClassId;
        final Long resolvedSectionId;

        private Classification(AdoptionOutcome outcome, String reason, Long resolvedClassId, Long resolvedSectionId) {
            this.outcome = outcome;
            this.reason = reason;
            this.resolvedClassId = resolvedClassId;
            this.resolvedSectionId = resolvedSectionId;
        }

        static Classification invalid(String reason) {
            return new Classification(AdoptionOutcome.INVALID_OR_CONFLICTING, reason, null, null);
        }

        static Classification requiresConfirmation(String reason, Long classId, Long sectionId) {
            return new Classification(AdoptionOutcome.REQUIRES_ADMIN_CONFIRMATION, reason, classId, sectionId);
        }

        static Classification safeCandidate(Long classId, Long sectionId) {
            return new Classification(null, null, classId, sectionId);
        }

        static Classification safe(Long classId, Long sectionId) {
            return new Classification(AdoptionOutcome.SAFE,
                    "Resolves cleanly to a class/section with no existing or conflicting responsibility.",
                    classId, sectionId);
        }

        static Classification alreadyAdopted(Long classId, Long sectionId) {
            return new Classification(AdoptionOutcome.ALREADY_ADOPTED,
                    "An identical responsibility row already exists for this class/section/teacher.",
                    classId, sectionId);
        }
    }

    static final class Counters {
        int scanned;
        int safe;
        int requiresAdminConfirmation;
        int invalidOrConflicting;
        int alreadyAdopted;
        int adopted;
    }

    record ClassificationResult(Counters counters, List<ResponsibilityCandidate> details) {}
}
