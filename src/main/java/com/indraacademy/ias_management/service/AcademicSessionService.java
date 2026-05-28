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

        AcademicSession session = sessionRepository.findById(sessionId)
                .filter(s -> s.getSchoolId().equals(schoolId))
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

    @Transactional
    public void deleteSession(Long sessionId) {
        Long schoolId = securityUtil.getSchoolId();

        AcademicSession session = sessionRepository.findById(sessionId)
                .filter(s -> s.getSchoolId().equals(schoolId))
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

    private AcademicSessionDto toDto(AcademicSession entity) {
        return new AcademicSessionDto(
                entity.getId(),
                entity.getLabel(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.isCurrent()
        );
    }
}
