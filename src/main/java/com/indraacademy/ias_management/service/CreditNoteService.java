package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.CreditNoteDto;
import com.indraacademy.ias_management.entity.*;
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

    @Transactional
    public CreditNoteDto createCreditNote(CreditNoteDto dto, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();

        // Validate student
        studentRepository.findByStudentId(dto.getStudentId())
                .filter(s -> s.getSchoolId().equals(schoolId))
                .orElseThrow(() -> new IllegalArgumentException("Student not found."));

        CreditNote creditNote = new CreditNote();
        creditNote.setSchoolId(schoolId);
        creditNote.setStudentId(dto.getStudentId());
        creditNote.setCreditType(CreditType.valueOf(dto.getCreditType()));
        creditNote.setAmount(dto.getAmount());
        creditNote.setReason(dto.getReason());
        creditNote.setStatus(CreditNoteStatus.PENDING);
        creditNote.setCreatedBy(securityUtil.getUsername());

        if (dto.getInvoiceId() != null) {
            Invoice invoice = invoiceRepository.findByIdAndSchoolId(dto.getInvoiceId(), schoolId)
                    .orElseThrow(() -> new IllegalArgumentException("Invoice not found."));
            creditNote.setInvoice(invoice);
        }

        CreditNote saved = creditNoteRepository.save(creditNote);

        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "CREATE_CREDIT_NOTE", "CreditNote", String.valueOf(saved.getId()),
                    null, objectMapper.writeValueAsString(toDto(saved)),
                    request.getRemoteAddr());
        } catch (JsonProcessingException ignored) {}

        return toDto(saved);
    }

    @Transactional
    public CreditNoteDto approveCreditNote(Long creditNoteId, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();

        CreditNote creditNote = creditNoteRepository.findByIdAndSchoolId(creditNoteId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Credit note not found."));

        if (creditNote.getStatus() != CreditNoteStatus.PENDING) {
            throw new IllegalStateException("Credit note is not in PENDING status.");
        }

        creditNote.setStatus(CreditNoteStatus.APPROVED);
        creditNote.setApprovedBy(securityUtil.getUsername());
        creditNote.setApprovedAt(LocalDateTime.now());

        // If linked to an invoice, apply the credit
        if (creditNote.getInvoice() != null) {
            Invoice invoice = creditNote.getInvoice();
            long newBalance = Math.max(0, invoice.getBalanceDue() - creditNote.getAmount());
            long creditApplied = invoice.getBalanceDue() - newBalance;

            invoice.setAmountPaid(invoice.getAmountPaid() + creditApplied);
            invoice.setBalanceDue(newBalance);

            if (newBalance == 0) {
                invoice.setStatus(InvoiceStatus.PAID);
            } else if (invoice.getAmountPaid() > 0) {
                invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
            }

            invoiceRepository.save(invoice);
            creditNote.setStatus(CreditNoteStatus.APPLIED);
        }

        CreditNote saved = creditNoteRepository.save(creditNote);

        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(),
                    "APPROVE_CREDIT_NOTE", "CreditNote", String.valueOf(saved.getId()),
                    null, objectMapper.writeValueAsString(toDto(saved)),
                    request.getRemoteAddr());
        } catch (JsonProcessingException ignored) {}

        return toDto(saved);
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
        return page.map(this::toDto);
    }

    private CreditNoteDto toDto(CreditNote cn) {
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

        studentRepository.findByStudentId(cn.getStudentId())
                .ifPresent(s -> dto.setStudentName(s.getName()));

        return dto;
    }
}
