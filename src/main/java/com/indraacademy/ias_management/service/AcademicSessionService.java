package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AcademicSessionDto;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import com.indraacademy.ias_management.repository.InvoiceRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeeConfigRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AcademicSessionService {

    @Autowired
    private AcademicSessionRepository sessionRepository;

    @Autowired
    private SchoolRepository schoolRepository;

    @Autowired
    private FeeStructureRuleRepository feeStructureRuleRepository;

    @Autowired
    private StudentFeeConfigRepository studentFeeConfigRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private SecurityUtil securityUtil;

    @Transactional(readOnly = true)
    public List<AcademicSessionDto> getAllSessions() {
        Long schoolId = securityUtil.getSchoolId();
        return sessionRepository.findBySchoolIdOrderByStartDateDesc(schoolId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AcademicSessionDto getCurrentSession() {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession session = sessionRepository.findBySchoolIdAndCurrentTrue(schoolId)
                .orElseThrow(() -> new IllegalStateException("No current academic session found. Please create one."));
        return toDto(session);
    }

    @Transactional(readOnly = true)
    public AcademicSession getCurrentSessionEntity() {
        Long schoolId = securityUtil.getSchoolId();
        return sessionRepository.findBySchoolIdAndCurrentTrue(schoolId)
                .orElseThrow(() -> new IllegalStateException("No current academic session found."));
    }

    /** Canonical school-scoped session lookup by id — a caller-supplied sessionId belonging
     *  to a different school never resolves, regardless of whether that id exists at all. */
    @Transactional(readOnly = true)
    public Optional<AcademicSession> getSessionById(Long schoolId, Long sessionId) {
        return sessionRepository.findByIdAndSchoolId(sessionId, schoolId);
    }

    @Transactional(readOnly = true)
    public Optional<AcademicSession> getSessionByLabel(Long schoolId, String label) {
        return sessionRepository.findBySchoolIdAndLabel(schoolId, label);
    }

    /** The session whose date range contains the given date (inclusive of both boundaries),
     *  or empty if the date falls outside every session configured for the school — e.g. a
     *  date before the school's first session or after its last one. */
    @Transactional(readOnly = true)
    public Optional<AcademicSession> getSessionForDate(Long schoolId, LocalDate date) {
        return sessionRepository.findBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                schoolId, date, date);
    }

    /** The session immediately before the given one (by start date), or empty if it's the
     *  school's earliest session. Empty also if sessionId doesn't resolve for this school. */
    @Transactional(readOnly = true)
    public Optional<AcademicSession> getPreviousSession(Long schoolId, Long sessionId) {
        return getSessionById(schoolId, sessionId)
                .flatMap(session -> sessionRepository
                        .findFirstBySchoolIdAndStartDateLessThanOrderByStartDateDesc(schoolId, session.getStartDate()));
    }

    /** The session immediately after the given one (by start date), or empty if it's the
     *  school's latest session. Empty also if sessionId doesn't resolve for this school. */
    @Transactional(readOnly = true)
    public Optional<AcademicSession> getNextSession(Long schoolId, Long sessionId) {
        return getSessionById(schoolId, sessionId)
                .flatMap(session -> sessionRepository
                        .findFirstBySchoolIdAndStartDateGreaterThanOrderByStartDateAsc(schoolId, session.getStartDate()));
    }

    @Transactional
    public AcademicSessionDto createSession(AcademicSessionDto dto) {
        Long schoolId = securityUtil.getSchoolId();

        if (sessionRepository.existsBySchoolIdAndLabel(schoolId, dto.getLabel())) {
            throw new IllegalArgumentException("Session '" + dto.getLabel() + "' already exists.");
        }

        AcademicSession session = new AcademicSession();
        session.setSchoolId(schoolId);
        session.setLabel(dto.getLabel());
        session.setStartDate(dto.getStartDate());
        session.setEndDate(dto.getEndDate());

        if (dto.isCurrent()) {
            // Unset any existing current session
            sessionRepository.findBySchoolIdAndCurrentTrue(schoolId)
                    .ifPresent(existing -> {
                        existing.setCurrent(false);
                        sessionRepository.save(existing);
                    });
            session.setCurrent(true);
        }

        return toDto(sessionRepository.save(session));
    }

    @Transactional
    public AcademicSessionDto setCurrentSession(Long sessionId) {
        Long schoolId = securityUtil.getSchoolId();

        // Unset existing current
        sessionRepository.findBySchoolIdAndCurrentTrue(schoolId)
                .ifPresent(existing -> {
                    existing.setCurrent(false);
                    sessionRepository.save(existing);
                });

        AcademicSession session = getSessionById(schoolId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found."));

        session.setCurrent(true);
        return toDto(sessionRepository.save(session));
    }

    /**
     * Auto-create session from school's academicYearStartMonth if none exists.
     * Called during onboarding or first fee setup.
     */
    @Transactional
    public AcademicSession getOrCreateCurrentSession(Long schoolId) {
        return sessionRepository.findBySchoolIdAndCurrentTrue(schoolId)
                .orElseGet(() -> {
                    School school = schoolRepository.findById(schoolId)
                            .orElseThrow(() -> new IllegalArgumentException("School not found"));
                    int startMonth = school.getAcademicYearStartMonth();
                    LocalDate now = LocalDate.now();

                    int startYear = now.getMonthValue() >= startMonth ? now.getYear() : now.getYear() - 1;
                    int endYear = startYear + 1;

                    AcademicSession session = new AcademicSession();
                    session.setSchoolId(schoolId);
                    session.setLabel(startYear + "-" + endYear);
                    session.setStartDate(LocalDate.of(startYear, startMonth, 1));
                    session.setEndDate(LocalDate.of(endYear, startMonth, 1).minusDays(1));
                    session.setCurrent(true);

                    return sessionRepository.save(session);
                });
    }

    /**
     * Convert academic month (1-12) to calendar date for a given session.
     */
    public LocalDate academicMonthToDate(AcademicSession session, int academicMonth) {
        LocalDate start = session.getStartDate();
        return start.plusMonths(academicMonth - 1);
    }

    /**
     * Inverse of academicMonthToDate: which academic month (1-12) a calendar date falls in,
     * relative to THIS session's own start date — never the school's academicYearStartMonth
     * config, so a session with non-standard boundaries (a manually-edited start date that
     * doesn't match the school-wide convention) is still resolved correctly. Wraps modulo-12
     * defensively so a date outside the session's actual [startDate, endDate] range (e.g. a
     * stale "current" flag that wasn't rolled over in time) still returns a plausible 1-12
     * value rather than 0/negative/>12 — callers that care about that drift should compare
     * the date against session.getStartDate()/getEndDate() themselves.
     */
    public int academicMonthForDate(AcademicSession session, LocalDate date) {
        long months = java.time.temporal.ChronoUnit.MONTHS.between(
                session.getStartDate().withDayOfMonth(1), date.withDayOfMonth(1));
        return (int) (((months % 12) + 12) % 12) + 1;
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        Long schoolId = securityUtil.getSchoolId();

        AcademicSession session = getSessionById(schoolId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found."));

        if (session.isCurrent()) {
            throw new IllegalStateException("Cannot delete the current academic session. Set another session as current first.");
        }

        if (invoiceRepository.existsBySchoolIdAndAcademicSessionId(schoolId, sessionId)) {
            throw new IllegalStateException("Cannot delete this session because invoices exist for it. Archive the session instead.");
        }

        // Cascade delete fee structure rules and student fee configs
        feeStructureRuleRepository.deleteBySchoolIdAndAcademicSessionId(schoolId, sessionId);
        studentFeeConfigRepository.deleteBySchoolIdAndAcademicSessionId(schoolId, sessionId);

        sessionRepository.delete(session);
    }

    /** Package-private (not public) — reused by {@link AcademicSessionActivationService} so the
     *  entity→DTO mapping isn't duplicated, without widening this service's public API. */
    AcademicSessionDto toDto(AcademicSession entity) {
        return new AcademicSessionDto(
                entity.getId(),
                entity.getLabel(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.isCurrent()
        );
    }
}
