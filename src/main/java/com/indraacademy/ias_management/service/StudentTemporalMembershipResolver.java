package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only, domain-neutral authority for historical student membership.
 * Attendance, results, and report cards may consume the classifications returned here, but
 * domain evidence and domain-specific fallback deliberately do not belong in this service.
 */
@Service
@Transactional(readOnly = true)
public class StudentTemporalMembershipResolver {

    private final StudentRepository studentRepository;
    private final AcademicSessionRepository sessionRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    public StudentTemporalMembershipResolver(
            StudentRepository studentRepository,
            AcademicSessionRepository sessionRepository,
            StudentEnrollmentRepository enrollmentRepository) {
        this.studentRepository = studentRepository;
        this.sessionRepository = sessionRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    public DateResolution resolveEffectiveRealizedEnrollment(
            Long schoolId, String studentId, LocalDate date) {
        requireStudent(schoolId, studentId);
        AcademicSession session = requireUniqueSessionContaining(schoolId, date);
        return resolveEffectiveRealizedEnrollment(schoolId, studentId, session.getId(), date);
    }

    public Session resolveTenantSession(Long schoolId, Long sessionId) {
        return sessionView(requireTenantSession(schoolId, sessionId));
    }

    public Session resolveTenantSession(Long schoolId, String sessionLabel) {
        if (schoolId == null || sessionLabel == null || sessionLabel.isBlank()) {
            throw new IllegalArgumentException("schoolId and sessionLabel are required");
        }
        AcademicSession session = sessionRepository.findBySchoolIdAndLabel(schoolId, sessionLabel)
                .orElseThrow(() -> new NoSuchElementException("Academic session not found for school"));
        return sessionView(session);
    }

    public DateResolution resolveEffectiveRealizedEnrollment(
            Long schoolId, String studentId, Long sessionId, LocalDate date) {
        requireStudent(schoolId, studentId);
        AcademicSession session = requireTenantSession(schoolId, sessionId);
        requireDateInSession(session, date);

        LocalDate adoptionBoundary = earliestRealizedEnrollmentDateInternal(schoolId, studentId).orElse(null);
        List<StudentEnrollment> matches = enrollmentRepository.findRealizedEffectiveEnrollments(
                schoolId, studentId, sessionId, date);
        String conflict = validateNoAmbiguity(matches, schoolId, studentId, sessionId);
        if (conflict != null) {
            return new DateResolution(sessionView(session), CoverageClassification.CONFLICT,
                    null, adoptionBoundary, false, conflict);
        }
        if (matches.size() > 1) {
            return new DateResolution(sessionView(session), CoverageClassification.CONFLICT,
                    null, adoptionBoundary, false,
                    "Multiple realized enrollment segments cover the requested date");
        }
        if (matches.size() == 1) {
            return new DateResolution(sessionView(session), CoverageClassification.ENROLLMENT_BACKED,
                    segmentView(matches.getFirst()), adoptionBoundary, false, null);
        }
        CoverageClassification classification = missingCoverage(date, adoptionBoundary);
        return new DateResolution(sessionView(session), classification, null, adoptionBoundary,
                classification == CoverageClassification.LEGACY_UNCOVERED, null);
    }

    public SessionResolution realizedEnrollmentSegmentsForSession(
            Long schoolId, String studentId, Long sessionId) {
        requireStudent(schoolId, studentId);
        AcademicSession session = requireTenantSession(schoolId, sessionId);
        LocalDate adoptionBoundary = earliestRealizedEnrollmentDateInternal(schoolId, studentId).orElse(null);
        List<StudentEnrollment> rows = enrollmentRepository.findRealizedByStudentAndSession(
                schoolId, studentId, sessionId);
        String conflict = validateNoAmbiguity(rows, schoolId, studentId, sessionId);
        if (conflict != null) {
            return new SessionResolution(sessionView(session), CoverageClassification.CONFLICT,
                    List.of(), adoptionBoundary, false, conflict);
        }
        List<Segment> segments = rows.stream().map(this::segmentView).toList();
        CoverageClassification classification = segments.isEmpty()
                ? missingCoverage(session.getEndDate(), adoptionBoundary)
                : CoverageClassification.ENROLLMENT_BACKED;
        return new SessionResolution(sessionView(session), classification, segments,
                adoptionBoundary, classification == CoverageClassification.LEGACY_UNCOVERED, null);
    }

    public RangeResolution resolveRealizedEnrollmentRange(
            Long schoolId, String studentId, Long sessionId, LocalDate from, LocalDate to) {
        requireRange(from, to);
        requireStudent(schoolId, studentId);
        AcademicSession session = requireTenantSession(schoolId, sessionId);
        requireDateInSession(session, from);
        requireDateInSession(session, to);

        LocalDate adoptionBoundary = earliestRealizedEnrollmentDateInternal(schoolId, studentId).orElse(null);
        List<StudentEnrollment> rows = enrollmentRepository.findRealizedOverlappingRange(
                schoolId, studentId, sessionId, from, to);
        rows = rows.stream()
                .sorted(Comparator.comparing(StudentEnrollment::getEffectiveFrom)
                        .thenComparing(StudentEnrollment::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        String conflict = validateNoAmbiguity(rows, schoolId, studentId, sessionId);
        if (conflict != null) {
            return new RangeResolution(sessionView(session), CoverageClassification.CONFLICT,
                    List.of(), List.of(), adoptionBoundary, false, conflict);
        }

        List<SegmentIntersection> intersections = rows.stream()
                .map(row -> intersection(row, from, to))
                .toList();
        List<UncoveredInterval> uncovered = uncoveredIntervals(from, to, intersections, adoptionBoundary);
        CoverageClassification classification;
        if (!intersections.isEmpty()) {
            classification = CoverageClassification.ENROLLMENT_BACKED;
        } else {
            classification = missingCoverage(to, adoptionBoundary);
        }
        boolean fallbackPermitted = intersections.isEmpty()
                && classification == CoverageClassification.LEGACY_UNCOVERED;
        return new RangeResolution(sessionView(session), classification, intersections, uncovered,
                adoptionBoundary, fallbackPermitted, null);
    }

    public Optional<LocalDate> earliestRealizedEnrollmentDate(Long schoolId, String studentId) {
        requireStudent(schoolId, studentId);
        return earliestRealizedEnrollmentDateInternal(schoolId, studentId);
    }

    private Optional<LocalDate> earliestRealizedEnrollmentDateInternal(Long schoolId, String studentId) {
        return enrollmentRepository.findRealizedHistory(schoolId, studentId).stream()
                .map(StudentEnrollment::getEffectiveFrom)
                .min(LocalDate::compareTo);
    }

    private AcademicSession requireUniqueSessionContaining(Long schoolId, LocalDate date) {
        if (schoolId == null || date == null) {
            throw new IllegalArgumentException("schoolId and date are required");
        }
        List<AcademicSession> sessions = sessionRepository
                .findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(schoolId, date, date);
        if (sessions.isEmpty()) {
            throw new NoSuchElementException("No academic session contains the requested date for school");
        }
        if (sessions.size() > 1) {
            throw new TemporalMembershipConflictException(
                    "Multiple tenant academic sessions contain the requested date");
        }
        return sessions.getFirst();
    }

    private AcademicSession requireTenantSession(Long schoolId, Long sessionId) {
        if (schoolId == null || sessionId == null) {
            throw new IllegalArgumentException("schoolId and sessionId are required");
        }
        return sessionRepository.findByIdAndSchoolId(sessionId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Academic session not found for school"));
    }

    private void requireStudent(Long schoolId, String studentId) {
        if (schoolId == null || studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("schoolId and studentId are required");
        }
        if (studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).isEmpty()) {
            throw new NoSuchElementException("Student not found for school");
        }
    }

    private void requireDateInSession(AcademicSession session, LocalDate date) {
        if (date == null) throw new IllegalArgumentException("date is required");
        if (date.isBefore(session.getStartDate()) || date.isAfter(session.getEndDate())) {
            throw new IllegalArgumentException("Requested date is outside the academic session");
        }
    }

    private void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new IllegalArgumentException("from and to are required");
        if (to.isBefore(from)) throw new IllegalArgumentException("to cannot precede from");
    }

    private CoverageClassification missingCoverage(LocalDate date, LocalDate adoptionBoundary) {
        return adoptionBoundary == null || date.isBefore(adoptionBoundary)
                ? CoverageClassification.LEGACY_UNCOVERED
                : CoverageClassification.AUTHORITATIVE_GAP;
    }

    private String validateNoAmbiguity(List<StudentEnrollment> rows, Long schoolId,
                                       String studentId, Long sessionId) {
        StudentEnrollment previous = null;
        for (StudentEnrollment row : rows.stream()
                .sorted(Comparator.comparing(StudentEnrollment::getEffectiveFrom)
                        .thenComparing(StudentEnrollment::getId, Comparator.nullsLast(Long::compareTo)))
                .toList()) {
            if (!Objects.equals(row.getSchoolId(), schoolId)
                    || !Objects.equals(row.getStudentId(), studentId)
                    || !Objects.equals(row.getAcademicSessionId(), sessionId)) {
                return "Repository returned enrollment outside the requested tenant context";
            }
            if (!isRealized(row.getStatus())) {
                return "Repository returned a non-realized enrollment";
            }
            if (row.getEffectiveFrom() == null
                    || (row.getEffectiveUntil() != null
                    && row.getEffectiveUntil().isBefore(row.getEffectiveFrom()))) {
                return "Repository returned an invalid enrollment interval";
            }
            if (previous != null && overlaps(previous, row)) {
                return "Multiple realized enrollment segments overlap";
            }
            previous = row;
        }
        return null;
    }

    private boolean isRealized(StudentEnrollmentStatus status) {
        return status == StudentEnrollmentStatus.ACTIVE || status == StudentEnrollmentStatus.CLOSED;
    }

    private boolean overlaps(StudentEnrollment left, StudentEnrollment right) {
        return left.getEffectiveUntil() == null
                || !left.getEffectiveUntil().isBefore(right.getEffectiveFrom());
    }

    private SegmentIntersection intersection(StudentEnrollment row, LocalDate from, LocalDate to) {
        LocalDate intersectionFrom = row.getEffectiveFrom().isAfter(from) ? row.getEffectiveFrom() : from;
        LocalDate rowUntil = row.getEffectiveUntil() == null ? to : row.getEffectiveUntil();
        LocalDate intersectionTo = rowUntil.isBefore(to) ? rowUntil : to;
        return new SegmentIntersection(segmentView(row), intersectionFrom, intersectionTo);
    }

    private List<UncoveredInterval> uncoveredIntervals(
            LocalDate from, LocalDate to, List<SegmentIntersection> intersections,
            LocalDate adoptionBoundary) {
        List<UncoveredInterval> result = new ArrayList<>();
        LocalDate cursor = from;
        for (SegmentIntersection intersection : intersections) {
            if (cursor.isBefore(intersection.intersectedFrom())) {
                addClassifiedUncovered(result, cursor, intersection.intersectedFrom().minusDays(1), adoptionBoundary);
            }
            if (!cursor.isAfter(intersection.intersectedTo())) {
                cursor = intersection.intersectedTo().plusDays(1);
            }
        }
        if (!cursor.isAfter(to)) addClassifiedUncovered(result, cursor, to, adoptionBoundary);
        return List.copyOf(result);
    }

    private void addClassifiedUncovered(
            List<UncoveredInterval> result, LocalDate from, LocalDate to, LocalDate adoptionBoundary) {
        if (adoptionBoundary == null || to.isBefore(adoptionBoundary)) {
            result.add(new UncoveredInterval(from, to, CoverageClassification.LEGACY_UNCOVERED, true));
            return;
        }
        if (from.isBefore(adoptionBoundary)) {
            result.add(new UncoveredInterval(from, adoptionBoundary.minusDays(1),
                    CoverageClassification.LEGACY_UNCOVERED, true));
            from = adoptionBoundary;
        }
        result.add(new UncoveredInterval(from, to, CoverageClassification.AUTHORITATIVE_GAP, false));
    }

    private Segment segmentView(StudentEnrollment row) {
        return new Segment(row.getId(), row.getSchoolId(), row.getStudentId(),
                row.getAcademicSessionId(), row.getClassId(), row.getClassNameSnapshot(),
                row.getSectionId(), row.getSectionNameSnapshot(), row.getStatus(),
                row.getEffectiveFrom(), row.getEffectiveUntil());
    }

    private Session sessionView(AcademicSession session) {
        return new Session(session.getId(), session.getSchoolId(), session.getLabel(),
                session.getStartDate(), session.getEndDate());
    }

    public enum CoverageClassification {
        ENROLLMENT_BACKED,
        LEGACY_UNCOVERED,
        AUTHORITATIVE_GAP,
        CONFLICT
    }

    public record Session(Long id, Long schoolId, String label, LocalDate startDate, LocalDate endDate) {}

    public record Segment(
            Long enrollmentId, Long schoolId, String studentId, Long academicSessionId,
            Long classId, String classNameSnapshot, Long sectionId, String sectionNameSnapshot,
            StudentEnrollmentStatus status, LocalDate effectiveFrom, LocalDate effectiveUntil) {}

    public record SegmentIntersection(
            Segment segment, LocalDate intersectedFrom, LocalDate intersectedTo) {}

    public record UncoveredInterval(
            LocalDate from, LocalDate to, CoverageClassification classification,
            boolean legacyFallbackPermitted) {}

    public record DateResolution(
            Session session, CoverageClassification classification, Segment segment,
            LocalDate adoptionBoundary, boolean legacyFallbackPermitted, String conflictReason) {
        public boolean enrollmentBacked() {
            return classification == CoverageClassification.ENROLLMENT_BACKED;
        }
    }

    public record SessionResolution(
            Session session, CoverageClassification classification, List<Segment> segments,
            LocalDate adoptionBoundary, boolean legacyFallbackPermitted, String conflictReason) {
        public SessionResolution {
            segments = List.copyOf(segments);
        }
    }

    public record RangeResolution(
            Session session, CoverageClassification classification,
            List<SegmentIntersection> intersections, List<UncoveredInterval> uncoveredIntervals,
            LocalDate adoptionBoundary, boolean legacyFallbackPermitted, String conflictReason) {
        public RangeResolution {
            intersections = List.copyOf(intersections);
            uncoveredIntervals = List.copyOf(uncoveredIntervals);
        }
    }

    public static class TemporalMembershipConflictException extends IllegalStateException {
        public TemporalMembershipConflictException(String message) {
            super(message);
        }
    }
}
