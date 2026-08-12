package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.CreditNoteDto;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.exception.SystemBFrozenException;
import com.indraacademy.ias_management.repository.CreditNoteRepository;
import com.indraacademy.ias_management.repository.InvoiceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CreditNoteService {

    @Autowired
    private CreditNoteRepository creditNoteRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * FROZEN (architecture decision — StudentFees/Payment/Refund is now the sole canonical
     * financial system). No new credit notes can be created. Existing ones remain fully
     * readable via getCreditNotes. If a waiver/write-off capability is needed going forward,
     * it belongs on the canonical ledger, not this system — see the architecture audit.
     * See SystemBFrozenException.
     */
    @Transactional
    public CreditNoteDto createCreditNote(CreditNoteDto dto, HttpServletRequest request) {
        throw new SystemBFrozenException("creating a credit note");
    }

    /**
     * FROZEN — same architecture decision as createCreditNote above. No PENDING credit note
     * can be newly approved (approval is itself a financial-state mutation: it changes
     * Invoice.balanceDue/amountPaid). Existing APPROVED/APPLIED credit notes are untouched.
     * See SystemBFrozenException.
     */
    @Transactional
    public CreditNoteDto approveCreditNote(Long creditNoteId, HttpServletRequest request) {
        throw new SystemBFrozenException("approving a credit note");
    }

    @Transactional(readOnly = true)
    public Page<CreditNoteDto> getCreditNotes(CreditNoteStatus status, Pageable pageable) {
        Long schoolId = securityUtil.getSchoolId();
        Page<CreditNote> page;
        if (status != null) {
            page = creditNoteRepository.findBySchoolIdAndStatus(schoolId, status, pageable);
        } else {
            page = creditNoteRepository.findBySchoolId(schoolId, pageable);
        }
        return page.map(cn -> toDto(cn, schoolId));
    }

    private CreditNoteDto toDto(CreditNote cn, Long schoolId) {
        CreditNoteDto dto = new CreditNoteDto();
        dto.setId(cn.getId());
        dto.setStudentId(cn.getStudentId());
        dto.setCreditType(cn.getCreditType().name());
        dto.setAmount(cn.getAmount());
        dto.setReason(cn.getReason());
        dto.setStatus(cn.getStatus().name());
        dto.setApprovedBy(cn.getApprovedBy());
        dto.setApprovedAt(cn.getApprovedAt());
        dto.setCreatedBy(cn.getCreatedBy());
        dto.setCreatedAt(cn.getCreatedAt());

        if (cn.getInvoice() != null) {
            dto.setInvoiceId(cn.getInvoice().getId());
            dto.setInvoiceNumber(cn.getInvoice().getInvoiceNumber());
        }

        studentRepository.findByStudentIdAndSchoolId(cn.getStudentId(), schoolId)
                .ifPresent(s -> dto.setStudentName(s.getName()));

        return dto;
    }
}
