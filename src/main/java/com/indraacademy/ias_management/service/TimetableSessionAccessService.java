package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.NoSuchElementException;

/**
 * Shared, single source of truth for "which AcademicSession may this write target" — used by
 * {@link TimetableService}, {@link TimetableBulkImportService}, {@link TimetableSessionCopyService}
 * (Phase F3), and now also {@link ClassTeacherResponsibilityService}/
 * {@link ClassTeacherActivationService} (Phase F4), so the tenant-ownership and
 * historical-write-block rules can never drift out of sync between write paths. The name predates
 * F4's non-timetable consumers but the class's actual role — AcademicSession access control — is
 * unchanged, so it was not renamed to avoid an unrelated diff across already-tested F3 code.
 */
@Service
public class TimetableSessionAccessService {

    @Autowired private AcademicSessionRepository academicSessionRepository;
    @Autowired private Clock clock;

    /** Tenant-safe lookup by explicit id — never resolves a session belonging to another school. */
    public AcademicSession requireOwnedSession(Long schoolId, Long academicSessionId) {
        if (academicSessionId == null) {
            throw new IllegalArgumentException("academicSessionId is required.");
        }
        return academicSessionRepository.findByIdAndSchoolId(academicSessionId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Academic session not found: " + academicSessionId));
    }

    /** Historical sessions are ADMIN read-only initially (approved F0/F1 decision) — a session
     *  that isn't current and has already ended cannot be written to by anyone, ADMIN included.
     *  A future (not yet started, not current) session IS writable — "future-session
     *  configuration: ADMIN only initially" only restricts WHO, not whether it can be edited. */
    public void requireWritable(AcademicSession session) {
        if (!session.isCurrent() && session.getEndDate().isBefore(LocalDate.now(clock))) {
            throw new IllegalStateException("Session " + session.getLabel() + " has ended and is read-only.");
        }
    }

    public AcademicSession currentSessionOrNull(Long schoolId) {
        return academicSessionRepository.findBySchoolIdAndCurrentTrue(schoolId).orElse(null);
    }

    /** The only session a TEACHER's self-service write may ever target — never a client-supplied
     *  value. Fails closed (rather than guessing) when the school has no current session. */
    public AcademicSession requireCurrentSessionForTeacherWrite(Long schoolId) {
        AcademicSession current = currentSessionOrNull(schoolId);
        if (current == null) {
            throw new IllegalStateException("No current academic session is configured for this school.");
        }
        return current;
    }

    /** ADMIN/SUPER_ADMIN write path: the requested session must be explicit, owned by this
     *  school, and not historical. */
    public AcademicSession requireWritableOwnedSession(Long schoolId, Long requestedSessionId) {
        AcademicSession session = requireOwnedSession(schoolId, requestedSessionId);
        requireWritable(session);
        return session;
    }

    /** Phase F4: pessimistic row lock on the session itself, held for the caller's whole
     *  transaction — serializes two concurrent callers targeting the same session (a
     *  class-teacher-responsibility write racing an activation apply(), or two concurrent
     *  apply() calls) without any new schema. Tenant-safe: never resolves another school's row. */
    public AcademicSession lockOwnedSession(Long schoolId, Long academicSessionId) {
        if (academicSessionId == null) {
            throw new IllegalArgumentException("academicSessionId is required.");
        }
        return academicSessionRepository.findByIdAndSchoolIdForUpdate(academicSessionId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Academic session not found: " + academicSessionId));
    }

    /** Locked variant of {@link #requireWritableOwnedSession} — acquires the row lock BEFORE
     *  checking historical-ness, so a concurrent activation apply() (which locks first too) can
     *  never interleave with this write mid-validation. */
    public AcademicSession lockWritableOwnedSession(Long schoolId, Long requestedSessionId) {
        AcademicSession session = lockOwnedSession(schoolId, requestedSessionId);
        requireWritable(session);
        return session;
    }
}
