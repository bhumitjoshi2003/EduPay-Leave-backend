package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationApplyResult;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationPreviewResult;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationRowResult;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationState;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ClassTeacherActivation;
import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.repository.ClassTeacherActivationRepository;
import com.indraacademy.ias_management.repository.ClassTeacherResponsibilityRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase F4: the ONLY path from "configured" (class_teacher_responsibility) to "live"
 * (Teacher.classTeacher/classTeacherSectionId) — and it is always explicit. Nothing else in this
 * codebase ever calls {@link #apply}: not session creation, not setCurrentSession, not timetable
 * copy, not responsibility copy. TeacherClassScopeService is untouched by this class and by this
 * whole file — it keeps reading only the two live Teacher columns, exactly as before.
 *
 * <p>{@link #apply} establishes the COMPLETE live class-teacher projection for the current
 * session, not an incremental merge: any teacher whose current live assignment is not backed by
 * an eligible, valid configured row for the current session is cleared, even if that teacher has
 * no row at all in the current session's configuration (see the class-level rationale in the F4
 * report — "do not accidentally retain a prior session's assignment").
 */
@Service
public class ClassTeacherActivationService {

    private static final Logger log = LoggerFactory.getLogger(ClassTeacherActivationService.class);

    @Autowired private ClassTeacherResponsibilityRepository responsibilityRepository;
    @Autowired private ClassTeacherActivationRepository activationRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private TimetableSessionAccessService sessionAccess;
    @Autowired private AcademicSessionService academicSessionService;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Clock clock;

    /** Read-only. Never writes. {@code inSync} answers "does live currently equal configured" —
     *  true by pure coincidence is possible (e.g. right after a session switch, before anyone
     *  activated anything) and must never be read as proof of activation. {@code activationState}
     *  is the separate, provenance-backed claim: whether this session's CURRENT configuration was
     *  ever explicitly applied, and whether it's still in sync with what was applied. Together
     *  these double as the fail-visible signal for a session switch that hasn't been activated
     *  yet — no polling/inference needed beyond calling this endpoint. */
    @Transactional(readOnly = true)
    public ActivationPreviewResult preview() {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession current = academicSessionService.getCurrentSessionEntity();
        return buildPreviewResult(schoolId, current);
    }

    /** As {@link #preview()}, but for an EXPLICIT target session rather than whatever is
     *  currently current — lets a caller (e.g. the "Make Current" confirmation) see what
     *  activating session X would do BEFORE X is actually made current. Tenant-scoped, read-only,
     *  never writes. Reuses the exact same diff/state logic as {@link #preview()} so the two can
     *  never drift out of sync with each other. */
    @Transactional(readOnly = true)
    public ActivationPreviewResult previewForSession(Long sessionId) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession session = sessionAccess.requireOwnedSession(schoolId, sessionId);
        return buildPreviewResult(schoolId, session);
    }

    private ActivationPreviewResult buildPreviewResult(Long schoolId, AcademicSession session) {
        Diff diff = computeDiff(schoolId, session.getId());
        boolean inSync = diff.becomingLive() == 0 && diff.changing() == 0 && diff.clearing() == 0;
        boolean hasIssues = diff.ineligibleCount() > 0 || diff.invalidCount() > 0;

        Optional<ClassTeacherActivation> record =
                activationRepository.findBySchoolIdAndAcademicSessionId(schoolId, session.getId());
        String currentFingerprint = fingerprint(diff.validConfigured());
        ActivationState state = resolveState(record, currentFingerprint, inSync);

        return new ActivationPreviewResult(session.getId(), inSync, state,
                record.map(ClassTeacherActivation::getAppliedAt).orElse(null),
                record.map(ClassTeacherActivation::getAppliedBy).orElse(null),
                hasIssues, diff.configuredCount(), diff.becomingLive(), diff.changing(), diff.unchanged(), diff.clearing(),
                diff.ineligibleCount(), diff.invalidCount(), diff.details());
    }

    /**
     * Applies the CURRENT session's configuration as the complete live class-teacher projection.
     * Single transaction — atomic from the product's perspective: if anything unexpected fails
     * partway, the whole apply rolls back and the live projection is left exactly as it was
     * before the call (see the F4 report for why this deliberately does NOT use the
     * per-row-isolated REQUIRES_NEW pattern the copy workers use). Locks the current session row
     * for the duration, serializing against a concurrent apply() or a concurrent
     * responsibility write for the same session. Revalidates (recomputes the diff) immediately
     * after acquiring the lock, never trusting a preview computed moments earlier.
     */
    @Transactional
    public ActivationApplyResult apply(HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession current = academicSessionService.getCurrentSessionEntity();
        AcademicSession locked = sessionAccess.lockOwnedSession(schoolId, current.getId());
        if (!locked.isCurrent()) {
            throw new IllegalStateException("The current session changed during activation; please retry.");
        }

        Diff diff = computeDiff(schoolId, locked.getId());

        // Build the complete target state per teacher: every currently-live teacher defaults to
        // CLEAR, then every valid+eligible configured row overwrites its teacher's target — this
        // is what makes activation a full replacement rather than an incremental merge.
        Map<String, TargetScope> targetByTeacher = new HashMap<>();
        for (Teacher liveTeacher : teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(schoolId)) {
            String liveKey = key(liveTeacher.getClassTeacher(), liveTeacher.getClassTeacherSectionId());
            if (diff.protectedSlotKeys.contains(liveKey)) {
                continue; // this slot's configured row is unusable (e.g. ineligible teacher) —
                          // leave whoever currently holds it completely untouched rather than
                          // clear them as a side effect of someone else's bad data entry.
            }
            targetByTeacher.put(liveTeacher.getTeacherId(), TargetScope.CLEAR);
        }
        for (ResolvedRow row : diff.validConfigured) {
            targetByTeacher.put(row.teacher.getTeacherId(), new TargetScope(row.className, row.sectionId));
        }

        int applied = 0, cleared = 0, unchanged = 0;
        for (Map.Entry<String, TargetScope> entry : targetByTeacher.entrySet()) {
            Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(entry.getKey(), schoolId)
                    .orElseThrow(() -> new IllegalStateException("Teacher disappeared during activation: " + entry.getKey()));
            TargetScope target = entry.getValue();
            if (target == TargetScope.CLEAR) {
                if (teacher.getClassTeacher() != null || teacher.getClassTeacherSectionId() != null) {
                    teacher.setClassTeacher(null);
                    teacher.setClassTeacherSectionId(null);
                    teacherRepository.save(teacher);
                    cleared++;
                } else {
                    unchanged++;
                }
            } else {
                boolean alreadyCorrect = Objects.equals(teacher.getClassTeacher(), target.className)
                        && Objects.equals(teacher.getClassTeacherSectionId(), target.sectionId);
                if (!alreadyCorrect) {
                    teacher.setClassTeacher(target.className);
                    teacher.setClassTeacherSectionId(target.sectionId);
                    teacherRepository.save(teacher);
                    applied++;
                } else {
                    unchanged++;
                }
            }
        }

        // Provenance write — same transaction as the Teacher writes above, so a failure anywhere
        // in this method rolls back the provenance record together with the live projection: a
        // failed activation can never record successful activation.
        String fingerprint = fingerprint(diff.validConfigured);
        LocalDateTime appliedAt = LocalDateTime.now(clock);
        String appliedBy = securityUtil.getUsername();
        ClassTeacherActivation record = activationRepository
                .findBySchoolIdAndAcademicSessionId(schoolId, locked.getId())
                .orElseGet(ClassTeacherActivation::new);
        record.setSchoolId(schoolId);
        record.setAcademicSessionId(locked.getId());
        record.setConfigurationFingerprint(fingerprint);
        record.setAppliedAt(appliedAt);
        record.setAppliedBy(appliedBy);
        activationRepository.save(record);

        // Trivially true by construction: every teacher named in diff.validConfigured was just
        // set to match, every teacher outside it (and not slot-protected) was just cleared, and
        // the fingerprint recorded above is exactly this configuration's — so state is
        // APPLIED_IN_SYNC immediately, with no need to re-run the comparison.
        ActivationApplyResult result = new ActivationApplyResult(
                locked.getId(), ActivationState.APPLIED_IN_SYNC, appliedAt, appliedBy,
                applied, cleared, unchanged, diff.ineligibleCount, diff.invalidCount, diff.details);

        log.warn("Class-teacher activation applied: sessionId={}, applied={}, cleared={}, unchanged={}, "
                        + "ineligible={}, invalidClassOrSection={}",
                locked.getId(), applied, cleared, unchanged, diff.ineligibleCount, diff.invalidCount);

        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "APPLY_CLASS_TEACHER_ACTIVATION",
                "AcademicSession", locked.getId().toString(), null, result.toString(), request.getRemoteAddr());

        return result;
    }

    // ── activation-state resolution ─────────────────────────────────────────────────────────

    /** NEVER_APPLIED if no record exists for this (school, session) at all. Otherwise
     *  APPLIED_IN_SYNC only if BOTH the recorded fingerprint still matches the current
     *  configuration AND live still matches configured right now — APPLIED_BUT_DRIFTED if
     *  either has changed since the recorded apply. Deliberately a content comparison, not a
     *  timestamp-based inference, so it stays correct under concurrent edits. */
    private ActivationState resolveState(Optional<ClassTeacherActivation> record, String currentFingerprint, boolean inSync) {
        if (record.isEmpty()) {
            return ActivationState.NEVER_APPLIED;
        }
        boolean fingerprintMatches = record.get().getConfigurationFingerprint().equals(currentFingerprint);
        return (fingerprintMatches && inSync) ? ActivationState.APPLIED_IN_SYNC : ActivationState.APPLIED_BUT_DRIFTED;
    }

    /** SHA-256 hex digest of the canonical, sorted (classId, sectionId, teacherId) set — a
     *  content fingerprint of exactly what a successful apply would grant, so two different
     *  configurations (even entered at different times) that happen to resolve to the same
     *  grants are correctly treated as equivalent, and any real change is reliably detected. */
    private String fingerprint(List<ResolvedRow> validConfigured) {
        List<String> parts = validConfigured.stream()
                .map(r -> r.classId() + ":" + r.sectionId() + ":" + r.teacher().getTeacherId())
                .sorted()
                .toList();
        String canonical = String.join("|", parts);
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

    // ── shared diff computation (preview and apply must never drift out of sync) ───────────

    private Diff computeDiff(Long schoolId, Long sessionId) {
        List<ClassTeacherResponsibility> configuredRows =
                responsibilityRepository.findByAcademicSessionIdAndSchoolId(sessionId, schoolId);

        List<ActivationRowResult> details = new ArrayList<>();
        List<ResolvedRow> validConfigured = new ArrayList<>();
        java.util.Set<String> protectedSlotKeys = new java.util.HashSet<>();
        int ineligibleCount = 0, invalidCount = 0;

        for (ClassTeacherResponsibility row : configuredRows) {
            SchoolClass schoolClass = schoolClassRepository.findByIdAndSchoolId(row.getClassId(), schoolId).orElse(null);
            if (schoolClass == null) {
                invalidCount++;
                details.add(new ActivationRowResult(row.getClassId(), null, row.getSectionId(), null,
                        row.getTeacherId(), null, null, null,
                        "SKIPPED_INVALID_CLASS", "Class " + row.getClassId() + " no longer exists"));
                continue;
            }
            Section section = null;
            if (row.getSectionId() != null) {
                section = sectionRepository.findByIdAndSchoolId(row.getSectionId(), schoolId).orElse(null);
                if (section == null || !Objects.equals(section.getClassId(), schoolClass.getId())) {
                    invalidCount++;
                    details.add(new ActivationRowResult(row.getClassId(), schoolClass.getName(), row.getSectionId(), null,
                            row.getTeacherId(), null, null, null,
                            "SKIPPED_INVALID_SECTION", "Section " + row.getSectionId() + " no longer resolves within this class"));
                    continue;
                }
            }
            Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(row.getTeacherId(), schoolId).orElse(null);
            if (teacher == null || teacher.getStatus() != TeacherStatus.ACTIVE) {
                ineligibleCount++;
                // This slot has a configured row this session — it's just unusable. Whoever
                // currently, live, holds this exact class/section must NOT be cleared as a side
                // effect of someone else's bad data entry: protect the slot rather than guess.
                protectedSlotKeys.add(key(schoolClass.getName(), row.getSectionId()));
                details.add(new ActivationRowResult(row.getClassId(), schoolClass.getName(), row.getSectionId(),
                        section != null ? section.getName() : null, row.getTeacherId(), null, null, null,
                        "SKIPPED_INELIGIBLE_TEACHER", "Teacher " + row.getTeacherId() + " is missing or not ACTIVE"));
                continue;
            }
            validConfigured.add(new ResolvedRow(schoolClass.getId(), schoolClass.getName(),
                    row.getSectionId(), section != null ? section.getName() : null, teacher));
        }

        int becomingLive = 0, changing = 0, unchanged = 0;
        Map<String, String> desiredKeyByTeacher = new HashMap<>(); // teacherId -> "className|sectionId"
        for (ResolvedRow row : validConfigured) {
            desiredKeyByTeacher.put(row.teacher.getTeacherId(), key(row.className, row.sectionId));
            Teacher priorLive = lookupLiveTeacher(schoolId, row.className, row.sectionId);
            String outcome;
            if (priorLive == null) {
                outcome = "BECOMING_LIVE";
                becomingLive++;
            } else if (Objects.equals(priorLive.getTeacherId(), row.teacher.getTeacherId())) {
                outcome = "UNCHANGED";
                unchanged++;
            } else {
                outcome = "CHANGING";
                changing++;
            }
            details.add(new ActivationRowResult(row.classId, row.className, row.sectionId, row.sectionName,
                    row.teacher.getTeacherId(), row.teacher.getName(),
                    priorLive != null ? priorLive.getTeacherId() : null,
                    priorLive != null ? priorLive.getName() : null,
                    outcome, null));
        }

        int clearing = 0;
        for (Teacher liveTeacher : teacherRepository.findBySchoolIdAndClassTeacherIsNotNull(schoolId)) {
            String liveKey = key(liveTeacher.getClassTeacher(), liveTeacher.getClassTeacherSectionId());
            String desiredKey = desiredKeyByTeacher.get(liveTeacher.getTeacherId());
            if (!liveKey.equals(desiredKey) && !protectedSlotKeys.contains(liveKey)) {
                clearing++;
                details.add(new ActivationRowResult(null, liveTeacher.getClassTeacher(),
                        liveTeacher.getClassTeacherSectionId(), null,
                        null, null, liveTeacher.getTeacherId(), liveTeacher.getName(),
                        "TO_BE_CLEARED", "Not represented in the current session's configuration"));
            }
        }

        return new Diff(configuredRows.size(), validConfigured, protectedSlotKeys, details, becomingLive, changing, unchanged, clearing, ineligibleCount, invalidCount);
    }

    private Teacher lookupLiveTeacher(Long schoolId, String className, Long sectionId) {
        List<Teacher> holders = sectionId != null
                ? teacherRepository.findByClassTeacherAndClassTeacherSectionIdAndSchoolId(className, sectionId, schoolId)
                : teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId(className, schoolId);
        return holders.isEmpty() ? null : holders.get(0);
    }

    private static String key(String className, Long sectionId) {
        return className + "|" + sectionId;
    }

    private record ResolvedRow(Long classId, String className, Long sectionId, String sectionName, Teacher teacher) {}

    private static final class TargetScope {
        static final TargetScope CLEAR = new TargetScope(null, null);
        final String className;
        final Long sectionId;
        TargetScope(String className, Long sectionId) { this.className = className; this.sectionId = sectionId; }
    }

    private record Diff(int configuredCount, List<ResolvedRow> validConfigured, java.util.Set<String> protectedSlotKeys,
                         List<ActivationRowResult> details,
                         int becomingLive, int changing, int unchanged, int clearing,
                         int ineligibleCount, int invalidCount) {
    }
}
