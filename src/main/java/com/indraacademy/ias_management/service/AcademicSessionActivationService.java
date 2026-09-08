package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AcademicSessionDto;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationApplyResult;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.SessionActivationOutcome;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase G: bridges the "Make Current" session-switch action to class-teacher activation in one
 * atomic step. The explicit session-switch action IS the deliberate activation event —
 * {@link ClassTeacherActivationService} itself remains the ONLY code that ever writes the live
 * Teacher projection, and nothing here duplicates its synchronization logic: this service locks
 * and switches the session, then delegates to the existing, unmodified
 * {@link ClassTeacherActivationService#apply}, which resolves "current" via
 * {@link AcademicSessionService#getCurrentSessionEntity()} and therefore correctly sees the
 * just-switched session within this same transaction (JPA auto-flushes the pending session-flag
 * writes before that lookup query executes).
 *
 * <p>Lives in its own service specifically to avoid a circular bean dependency:
 * {@link ClassTeacherActivationService} already depends on {@link AcademicSessionService}, so the
 * reverse direction (session service depending back on activation) can't live in either existing
 * service without creating a cycle. This class depends on both, unidirectionally.
 */
@Service
public class AcademicSessionActivationService {

    private static final Logger log = LoggerFactory.getLogger(AcademicSessionActivationService.class);

    @Autowired private TimetableSessionAccessService sessionAccess;
    @Autowired private ClassTeacherActivationService activationService;
    @Autowired private AcademicSessionService academicSessionService;
    @Autowired private SecurityUtil securityUtil;

    /**
     * Validates tenant ownership and locks the target session, then:
     * <ul>
     *   <li>if the target is ALREADY current: a genuine no-op — no session-flag rewrite, no
     *       activation call, no provenance touched. Re-running activation for a non-transition
     *       would misrepresent provenance as a fresh explicit event, which it is not.</li>
     *   <li>otherwise: reuses the existing, unmodified {@link AcademicSessionService#setCurrentSession}
     *       to switch the current-session flag, then calls the existing {@code apply()} — all in
     *       ONE transaction, so a failure anywhere (including inside {@code apply()}) rolls back
     *       the session switch too. Locking the target row upfront serves both tenant validation
     *       and serialization; {@code setCurrentSession}'s own (unlocked) re-read of the same row
     *       moments later is a harmless redundant SELECT within the transaction we already hold
     *       the lock in — not a race, since nothing else can intervene mid-transaction. Locking
     *       only the target (not also the outgoing "previous current" row) is a deliberate, scoped
     *       choice: {@code apply()} itself locks the now-current (target) row for the remainder of
     *       the operation, which is the row that actually matters for serializing against a
     *       concurrent responsibility write or a concurrent activation attempt; this is a rare,
     *       human-paced ADMIN action, not a high-concurrency hot path, so a second lock (with the
     *       dual-lock-ordering complexity it would require to stay deadlock-safe) was judged
     *       unnecessary here.</li>
     * </ul>
     */
    @Transactional
    public SessionActivationOutcome setCurrentSessionAndActivate(Long sessionId, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession target = sessionAccess.lockOwnedSession(schoolId, sessionId);

        if (target.isCurrent()) {
            log.info("setCurrentSessionAndActivate: session {} is already current for school {} — no-op.",
                    sessionId, schoolId);
            return new SessionActivationOutcome(academicSessionService.toDto(target), false, null);
        }

        AcademicSessionDto switched = academicSessionService.setCurrentSession(sessionId);
        ActivationApplyResult activation = activationService.apply(request);

        log.info("setCurrentSessionAndActivate: school {} switched current session to {} and activated "
                        + "its class-teacher configuration (applied={}, cleared={}, unchanged={}).",
                schoolId, sessionId, activation.applied(), activation.cleared(), activation.unchanged());

        return new SessionActivationOutcome(switched, true, activation);
    }
}
