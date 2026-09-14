package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AcademicSessionDto;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeeConfigRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 1A: AcademicSessionService as the canonical session lookup surface. Covers the
 * pre-existing CRUD/current-session behavior alongside the new getSessionById/getSessionByLabel/
 * getSessionForDate/getPreviousSession/getNextSession methods, with particular attention to
 * tenant scoping — every one of these must be inert against a sessionId that exists but belongs
 * to a different school.
 */
@ExtendWith(MockitoExtension.class)
class AcademicSessionServiceTest {

    @Mock AcademicSessionRepository sessionRepository;
    @Mock SchoolRepository schoolRepository;
    @Mock FeeStructureRuleRepository feeStructureRuleRepository;
    @Mock StudentFeeConfigRepository studentFeeConfigRepository;
    @Mock SecurityUtil securityUtil;

    private AcademicSessionService service;

    private static final Long SCHOOL_ID = 2L;
    private static final Long OTHER_SCHOOL_ID = 9L;

    @BeforeEach
    void setUp() {
        service = new AcademicSessionService();
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "feeStructureRuleRepository", feeStructureRuleRepository);
        ReflectionTestUtils.setField(service, "studentFeeConfigRepository", studentFeeConfigRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
    }

    private static AcademicSession session(Long id, Long schoolId, String label, LocalDate start, LocalDate end, boolean current) {
        AcademicSession s = new AcademicSession();
        s.setId(id);
        s.setSchoolId(schoolId);
        s.setLabel(label);
        s.setStartDate(start);
        s.setEndDate(end);
        s.setCurrent(current);
        return s;
    }

    // ── getCurrentSession / getCurrentSessionEntity ──────────────────────────────────────

    @Test
    void getCurrentSessionReturnsTheSchoolsCurrentSessionAsADto() {
        AcademicSession current = session(1L, SCHOOL_ID, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), true);
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.of(current));

        AcademicSessionDto dto = service.getCurrentSession();

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getLabel()).isEqualTo("2025-2026");
        assertThat(dto.isCurrent()).isTrue();
    }

    @Test
    void getCurrentSessionThrowsWhenNoCurrentSessionExists() {
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentSession()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getCurrentSessionEntityThrowsWhenNoCurrentSessionExists() {
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentSessionEntity()).isInstanceOf(IllegalStateException.class);
    }

    // ── getSessionById — including tenant isolation ──────────────────────────────────────

    @Test
    void getSessionByIdReturnsTheMatchingSessionForItsOwnSchool() {
        AcademicSession target = session(5L, SCHOOL_ID, "2024-2025",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), false);
        when(sessionRepository.findByIdAndSchoolId(5L, SCHOOL_ID)).thenReturn(Optional.of(target));

        assertThat(service.getSessionById(SCHOOL_ID, 5L)).contains(target);
    }

    @Test
    void getSessionByIdReturnsEmptyForASessionBelongingToAnotherSchool() {
        // The repository query itself is schoolId-scoped; a sessionId that exists but under a
        // different school simply never matches — proving the service never widens that query.
        when(sessionRepository.findByIdAndSchoolId(5L, SCHOOL_ID)).thenReturn(Optional.empty());

        assertThat(service.getSessionById(SCHOOL_ID, 5L)).isEmpty();
        verify(sessionRepository).findByIdAndSchoolId(5L, SCHOOL_ID);
    }

    @Test
    void getSessionByIdReturnsEmptyWhenIdDoesNotExistAtAll() {
        when(sessionRepository.findByIdAndSchoolId(404L, SCHOOL_ID)).thenReturn(Optional.empty());

        assertThat(service.getSessionById(SCHOOL_ID, 404L)).isEmpty();
    }

    // ── getSessionByLabel ─────────────────────────────────────────────────────────────────

    @Test
    void getSessionByLabelReturnsTheMatchingSession() {
        AcademicSession target = session(6L, SCHOOL_ID, "2023-2024",
                LocalDate.of(2023, 4, 1), LocalDate.of(2024, 3, 31), false);
        when(sessionRepository.findBySchoolIdAndLabel(SCHOOL_ID, "2023-2024")).thenReturn(Optional.of(target));

        assertThat(service.getSessionByLabel(SCHOOL_ID, "2023-2024")).contains(target);
    }

    @Test
    void getSessionByLabelReturnsEmptyWhenNoSuchLabelExistsForTheSchool() {
        when(sessionRepository.findBySchoolIdAndLabel(SCHOOL_ID, "1999-2000")).thenReturn(Optional.empty());

        assertThat(service.getSessionByLabel(SCHOOL_ID, "1999-2000")).isEmpty();
    }

    // ── getSessionForDate ─────────────────────────────────────────────────────────────────

    @Test
    void getSessionForDateAtTheStartBoundaryIsFound() {
        AcademicSession current = session(1L, SCHOOL_ID, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), true);
        LocalDate startBoundary = current.getStartDate();
        when(sessionRepository.findBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                SCHOOL_ID, startBoundary, startBoundary)).thenReturn(Optional.of(current));

        assertThat(service.getSessionForDate(SCHOOL_ID, startBoundary)).contains(current);
    }

    @Test
    void getSessionForDateAtTheEndBoundaryIsFound() {
        AcademicSession current = session(1L, SCHOOL_ID, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), true);
        LocalDate endBoundary = current.getEndDate();
        when(sessionRepository.findBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                SCHOOL_ID, endBoundary, endBoundary)).thenReturn(Optional.of(current));

        assertThat(service.getSessionForDate(SCHOOL_ID, endBoundary)).contains(current);
    }

    @Test
    void getSessionForDateOutsideEverySessionReturnsEmpty() {
        LocalDate farInThePast = LocalDate.of(1999, 1, 1);
        when(sessionRepository.findBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                SCHOOL_ID, farInThePast, farInThePast)).thenReturn(Optional.empty());

        assertThat(service.getSessionForDate(SCHOOL_ID, farInThePast)).isEmpty();
    }

    // ── getPreviousSession / getNextSession ──────────────────────────────────────────────

    @Test
    void getPreviousSessionReturnsTheImmediatelyEarlierSessionByStartDate() {
        AcademicSession current = session(2L, SCHOOL_ID, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), true);
        AcademicSession previous = session(1L, SCHOOL_ID, "2024-2025",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), false);
        when(sessionRepository.findByIdAndSchoolId(2L, SCHOOL_ID)).thenReturn(Optional.of(current));
        when(sessionRepository.findFirstBySchoolIdAndStartDateLessThanOrderByStartDateDesc(
                SCHOOL_ID, current.getStartDate())).thenReturn(Optional.of(previous));

        assertThat(service.getPreviousSession(SCHOOL_ID, 2L)).contains(previous);
    }

    @Test
    void getNextSessionReturnsTheImmediatelyLaterSessionByStartDate() {
        AcademicSession current = session(2L, SCHOOL_ID, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), true);
        AcademicSession next = session(3L, SCHOOL_ID, "2026-2027",
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), false);
        when(sessionRepository.findByIdAndSchoolId(2L, SCHOOL_ID)).thenReturn(Optional.of(current));
        when(sessionRepository.findFirstBySchoolIdAndStartDateGreaterThanOrderByStartDateAsc(
                SCHOOL_ID, current.getStartDate())).thenReturn(Optional.of(next));

        assertThat(service.getNextSession(SCHOOL_ID, 2L)).contains(next);
    }

    @Test
    void firstSessionHasNoPrevious() {
        AcademicSession earliest = session(1L, SCHOOL_ID, "2024-2025",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), false);
        when(sessionRepository.findByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(earliest));
        when(sessionRepository.findFirstBySchoolIdAndStartDateLessThanOrderByStartDateDesc(
                SCHOOL_ID, earliest.getStartDate())).thenReturn(Optional.empty());

        assertThat(service.getPreviousSession(SCHOOL_ID, 1L)).isEmpty();
    }

    @Test
    void lastSessionHasNoNext() {
        AcademicSession latest = session(3L, SCHOOL_ID, "2026-2027",
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), true);
        when(sessionRepository.findByIdAndSchoolId(3L, SCHOOL_ID)).thenReturn(Optional.of(latest));
        when(sessionRepository.findFirstBySchoolIdAndStartDateGreaterThanOrderByStartDateAsc(
                SCHOOL_ID, latest.getStartDate())).thenReturn(Optional.empty());

        assertThat(service.getNextSession(SCHOOL_ID, 3L)).isEmpty();
    }

    @Test
    void previousAndNextReturnEmptyRatherThanLeakingWhenSessionBelongsToAnotherSchool() {
        // sessionId 7 is real, but not under SCHOOL_ID — getSessionById correctly finds
        // nothing, and previous/next must short-circuit rather than falling back to some
        // other lookup that could cross the tenant boundary.
        when(sessionRepository.findByIdAndSchoolId(7L, SCHOOL_ID)).thenReturn(Optional.empty());

        assertThat(service.getPreviousSession(SCHOOL_ID, 7L)).isEmpty();
        assertThat(service.getNextSession(SCHOOL_ID, 7L)).isEmpty();
        verify(sessionRepository, never()).findFirstBySchoolIdAndStartDateLessThanOrderByStartDateDesc(any(), any());
        verify(sessionRepository, never()).findFirstBySchoolIdAndStartDateGreaterThanOrderByStartDateAsc(any(), any());
    }

    // ── createSession ─────────────────────────────────────────────────────────────────────

    @Test
    void createSessionPersistsANewNonCurrentSession() {
        when(sessionRepository.existsBySchoolIdAndLabel(SCHOOL_ID, "2027-2028")).thenReturn(false);
        when(sessionRepository.save(any(AcademicSession.class))).thenAnswer(inv -> inv.getArgument(0));

        AcademicSessionDto dto = new AcademicSessionDto(null, "2027-2028",
                LocalDate.of(2027, 4, 1), LocalDate.of(2028, 3, 31), false);

        AcademicSessionDto saved = service.createSession(dto);

        assertThat(saved.getLabel()).isEqualTo("2027-2028");
        assertThat(saved.isCurrent()).isFalse();
        verify(sessionRepository, times(1)).save(any(AcademicSession.class));
    }

    @Test
    void createSessionRejectsADuplicateLabelForTheSameSchool() {
        when(sessionRepository.existsBySchoolIdAndLabel(SCHOOL_ID, "2025-2026")).thenReturn(true);

        AcademicSessionDto dto = new AcademicSessionDto(null, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), false);

        assertThatThrownBy(() -> service.createSession(dto)).isInstanceOf(IllegalArgumentException.class);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void creatingASessionAsCurrentUnsetsThePreviousCurrentSession() {
        AcademicSession existingCurrent = session(1L, SCHOOL_ID, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), true);
        when(sessionRepository.existsBySchoolIdAndLabel(SCHOOL_ID, "2026-2027")).thenReturn(false);
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.of(existingCurrent));
        when(sessionRepository.save(any(AcademicSession.class))).thenAnswer(inv -> inv.getArgument(0));

        AcademicSessionDto dto = new AcademicSessionDto(null, "2026-2027",
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), true);

        ArgumentCaptor<AcademicSession> captor = ArgumentCaptor.forClass(AcademicSession.class);
        AcademicSessionDto saved = service.createSession(dto);

        verify(sessionRepository, times(2)).save(captor.capture());
        List<AcademicSession> savedEntities = captor.getAllValues();
        assertThat(savedEntities.get(0).isCurrent()).isFalse(); // the old current, unset
        assertThat(savedEntities.get(0).getLabel()).isEqualTo("2025-2026");
        assertThat(savedEntities.get(1).isCurrent()).isTrue();  // the newly created one
        assertThat(saved.isCurrent()).isTrue();
    }

    // ── setCurrentSession ─────────────────────────────────────────────────────────────────

    @Test
    void setCurrentSessionUnsetsThePreviousAndActivatesTheTarget() {
        AcademicSession existingCurrent = session(1L, SCHOOL_ID, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), true);
        AcademicSession target = session(2L, SCHOOL_ID, "2026-2027",
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), false);
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.of(existingCurrent));
        when(sessionRepository.findByIdAndSchoolId(2L, SCHOOL_ID)).thenReturn(Optional.of(target));
        when(sessionRepository.save(any(AcademicSession.class))).thenAnswer(inv -> inv.getArgument(0));

        AcademicSessionDto result = service.setCurrentSession(2L);

        assertThat(existingCurrent.isCurrent()).isFalse();
        assertThat(result.isCurrent()).isTrue();
        assertThat(result.getId()).isEqualTo(2L);
        verify(sessionRepository, times(2)).save(any(AcademicSession.class));
    }

    @Test
    void setCurrentSessionThrowsWhenTargetDoesNotBelongToTheCallersSchool() {
        when(sessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.empty());
        when(sessionRepository.findByIdAndSchoolId(99L, SCHOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setCurrentSession(99L)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── deleteSession ─────────────────────────────────────────────────────────────────────

    @Test
    void deleteSessionRemovesANonCurrentSession() {
        AcademicSession target = session(4L, SCHOOL_ID, "2022-2023",
                LocalDate.of(2022, 4, 1), LocalDate.of(2023, 3, 31), false);
        when(sessionRepository.findByIdAndSchoolId(4L, SCHOOL_ID)).thenReturn(Optional.of(target));

        service.deleteSession(4L);

        verify(feeStructureRuleRepository).deleteBySchoolIdAndAcademicSessionId(SCHOOL_ID, 4L);
        verify(studentFeeConfigRepository).deleteBySchoolIdAndAcademicSessionId(SCHOOL_ID, 4L);
        verify(sessionRepository).delete(target);
    }

    @Test
    void deleteSessionRejectsTheCurrentSession() {
        AcademicSession current = session(1L, SCHOOL_ID, "2025-2026",
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), true);
        when(sessionRepository.findByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.deleteSession(1L)).isInstanceOf(IllegalStateException.class);
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void deleteSessionThrowsWhenSessionDoesNotBelongToTheCallersSchool() {
        when(sessionRepository.findByIdAndSchoolId(anyLong(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteSession(123L)).isInstanceOf(IllegalArgumentException.class);
        verify(sessionRepository, never()).delete(any());
    }
}
