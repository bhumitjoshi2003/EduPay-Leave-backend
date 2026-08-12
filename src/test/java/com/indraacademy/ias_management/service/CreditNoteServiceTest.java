package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.CreditNoteDto;
import com.indraacademy.ias_management.entity.CreditNote;
import com.indraacademy.ias_management.entity.CreditNoteStatus;
import com.indraacademy.ias_management.entity.CreditType;
import com.indraacademy.ias_management.exception.SystemBFrozenException;
import com.indraacademy.ias_management.repository.CreditNoteRepository;
import com.indraacademy.ias_management.repository.InvoiceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * System B freeze phase: no new credit note may be created or approved — approving one is
 * itself a financial-state mutation (changes Invoice.balanceDue/amountPaid), not just a
 * status flip. Existing credit notes (any status) remain fully readable via getCreditNotes.
 */
@ExtendWith(MockitoExtension.class)
class CreditNoteServiceTest {

    @Mock private CreditNoteRepository creditNoteRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest httpServletRequest;

    private CreditNoteService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new CreditNoteService();
        ReflectionTestUtils.setField(service, "creditNoteRepository", creditNoteRepository);
        ReflectionTestUtils.setField(service, "invoiceRepository", invoiceRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
    }

    @Test
    void createCreditNote_rejectedByFreeze_noRecordCreated() {
        CreditNoteDto dto = new CreditNoteDto();
        dto.setStudentId("S1");
        dto.setCreditType("REFUND");
        dto.setAmount(50_000L);
        dto.setReason("Test refund request");

        assertThatThrownBy(() -> service.createCreditNote(dto, httpServletRequest))
                .isInstanceOf(SystemBFrozenException.class)
                .hasMessageContaining("creating a credit note");

        verifyNoInteractions(creditNoteRepository, invoiceRepository, studentRepository, auditService);
    }

    @Test
    void approveCreditNote_rejectedByFreeze_existingPendingNoteNeverTransitions() {
        assertThatThrownBy(() -> service.approveCreditNote(99L, httpServletRequest))
                .isInstanceOf(SystemBFrozenException.class)
                .hasMessageContaining("approving a credit note");

        verifyNoInteractions(creditNoteRepository, invoiceRepository, auditService);
    }

    @Test
    void getCreditNotes_existingHistoricalNotes_stillReadableAfterFreeze() {
        CreditNote note = new CreditNote();
        note.setId(5L);
        note.setSchoolId(SCHOOL_ID);
        note.setStudentId("S1");
        note.setCreditType(CreditType.ADJUSTMENT);
        note.setAmount(30_000L);
        note.setReason("Historical adjustment, pre-freeze");
        note.setStatus(CreditNoteStatus.APPROVED);
        note.setCreatedBy("admin");
        when(creditNoteRepository.findBySchoolId(eq(SCHOOL_ID), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(note)));

        var page = service.getCreditNotes(null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(5L);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo("APPROVED");
    }
}
