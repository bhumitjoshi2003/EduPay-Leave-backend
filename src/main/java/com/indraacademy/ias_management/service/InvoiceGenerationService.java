package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InvoiceGenerationService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceGenerationService.class);

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private FeeStructureRuleRepository ruleRepository;

    @Autowired
    private FeeHeadRepository feeHeadRepository;

    @Autowired
    private StudentFeeConfigRepository configRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AcademicSessionRepository sessionRepository;

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Generate invoices for a billing month. Core algorithm:
     *
     * 1. Determine which students need invoices (skip if already exists)
     * 2. For each student, find active fee_structure_rules for their class
     * 3. Check each rule's fee_head.due_months — only include if billingMonth is in due_months
     * 4. Apply student_fee_config overrides (discounts, waivers, opt-outs, custom amounts)
     * 5. Create DRAFT invoice with line items
     *
     * @param sessionId Academic session ID
     * @param billingMonth Academic month (1-12)
     * @param className Optional: filter to single class. Null = all classes.
     * @param studentId Optional: filter to single student. Null = all students.
     * @return Number of invoices generated
     */
    @Transactional
    public int generateInvoices(Long sessionId, int billingMonth, String className, String studentId) {
        Long schoolId = securityUtil.getSchoolId();

        AcademicSession session = sessionRepository.findById(sessionId)
                .filter(s -> s.getSchoolId().equals(schoolId))
                .orElseThrow(() -> new IllegalArgumentException("Session not found."));

        // Determine the calendar date for this billing month (for rule effective date checks)
        LocalDate billingDate = session.getStartDate().plusMonths(billingMonth - 1);
        LocalDate dueDate = billingDate.withDayOfMonth(Math.min(10, billingDate.lengthOfMonth()));

        // Get students who already have invoices for this month (idempotency)
        Set<String> alreadyInvoiced = new HashSet<>(
                invoiceRepository.findStudentsWithInvoice(schoolId, sessionId, billingMonth));

        // Get active students
        List<Student> students;
        if (studentId != null) {
            students = studentRepository.findByStudentId(studentId)
                    .filter(s -> s.getSchoolId().equals(schoolId))
                    .filter(s -> s.getStatus() == StudentStatus.ACTIVE)
                    .stream().collect(Collectors.toList());
        } else if (className != null) {
            students = studentRepository.findByClassNameAndStatusAndSchoolId(
                    className, StudentStatus.ACTIVE, schoolId);
        } else {
            students = studentRepository.findByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
        }

        // Group students by class for batch rule lookup
        Map<String, List<Student>> studentsByClass = students.stream()
                .filter(s -> !alreadyInvoiced.contains(s.getStudentId()))
                .collect(Collectors.groupingBy(Student::getClassName));

        int generatedCount = 0;

        for (Map.Entry<String, List<Student>> entry : studentsByClass.entrySet()) {
            String cls = entry.getKey();
            List<Student> classStudents = entry.getValue();

            // Get active rules for this class on the billing date
            List<FeeStructureRule> activeRules = ruleRepository.findActiveRules(
                    schoolId, sessionId, cls, billingDate);

            if (activeRules.isEmpty()) {
                log.warn("No active fee rules for class {} in session {}, skipping", cls, session.getLabel());
                continue;
            }

            // Filter rules to only those whose fee_head has this billingMonth in due_months
            List<FeeStructureRule> applicableRules = activeRules.stream()
                    .filter(rule -> isDueInMonth(rule.getFeeHead(), billingMonth))
                    .collect(Collectors.toList());

            if (applicableRules.isEmpty()) {
                continue; // No fees due this month for this class
            }

            for (Student student : classStudents) {
                Invoice invoice = generateStudentInvoice(
                        schoolId, session, student, applicableRules,
                        billingMonth, billingDate, dueDate);

                if (invoice != null) {
                    generatedCount++;
                }
            }
        }

        log.info("Generated {} invoices for session {} month {}",
                generatedCount, session.getLabel(), billingMonth);

        return generatedCount;
    }

    /**
     * Generate a single student's invoice for a billing month.
     */
    private Invoice generateStudentInvoice(
            Long schoolId,
            AcademicSession session,
            Student student,
            List<FeeStructureRule> applicableRules,
            int billingMonth,
            LocalDate billingDate,
            LocalDate dueDate) {

        // Load student-specific configs
        List<StudentFeeConfig> configs = configRepository.findActiveConfigs(
                schoolId, student.getStudentId(), session.getId(), billingDate);

        // Index configs by fee_head_id
        Map<Long, StudentFeeConfig> configByFeeHead = configs.stream()
                .collect(Collectors.toMap(
                        c -> c.getFeeHead().getId(),
                        c -> c,
                        (a, b) -> b // If multiple configs for same fee head, use the latest
                ));

        // Generate invoice number
        String invoiceNumber = generateInvoiceNumber(schoolId);

        Invoice invoice = new Invoice();
        invoice.setSchoolId(schoolId);
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setStudentId(student.getStudentId());
        invoice.setAcademicSession(session);
        invoice.setBillingMonth(billingMonth);
        invoice.setDueDate(dueDate);
        invoice.setStatus(InvoiceStatus.DRAFT);

        long totalBase = 0;
        long totalDiscount = 0;

        for (FeeStructureRule rule : applicableRules) {
            FeeHead feeHead = rule.getFeeHead();
            StudentFeeConfig config = configByFeeHead.get(feeHead.getId());

            // Check for OPT_OUT on optional fees
            if (feeHead.isOptional() && config != null && config.getConfigType() == FeeConfigType.OPT_OUT) {
                continue;
            }

            long baseAmount = rule.getAmount();
            long discountAmount = 0;

            if (config != null) {
                switch (config.getConfigType()) {
                    case WAIVER:
                        discountAmount = baseAmount; // Full waiver
                        break;
                    case DISCOUNT_PERCENT:
                        discountAmount = BigDecimal.valueOf(baseAmount)
                                .multiply(config.getValue())
                                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                                .longValue();
                        break;
                    case DISCOUNT_FIXED:
                        discountAmount = Math.min(config.getValue().longValue(), baseAmount);
                        break;
                    case CUSTOM_AMOUNT:
                        baseAmount = config.getValue().longValue();
                        break;
                    case OPT_OUT:
                        continue; // Already handled above, but just in case
                }
            }

            long netAmount = baseAmount - discountAmount;

            InvoiceLineItem lineItem = new InvoiceLineItem();
            lineItem.setFeeHead(feeHead);
            lineItem.setFeeHeadCode(feeHead.getCode());
            lineItem.setDescription(feeHead.getName());
            lineItem.setBaseAmount(baseAmount);
            lineItem.setDiscountAmount(discountAmount);
            lineItem.setNetAmount(netAmount);

            invoice.addLineItem(lineItem);

            totalBase += baseAmount;
            totalDiscount += discountAmount;
        }

        // If no line items, skip invoice creation
        if (invoice.getLineItems().isEmpty()) {
            return null;
        }

        long netAmount = totalBase - totalDiscount;

        invoice.setTotalAmount(totalBase);
        invoice.setDiscountAmount(totalDiscount);
        invoice.setNetAmount(netAmount);
        invoice.setAmountPaid(0);
        invoice.setBalanceDue(netAmount);

        return invoiceRepository.save(invoice);
    }

    /**
     * Check if a fee head is due in the given academic month.
     * Parses the due_months JSON array.
     */
    private boolean isDueInMonth(FeeHead feeHead, int billingMonth) {
        try {
            List<Integer> dueMonths = objectMapper.readValue(
                    feeHead.getDueMonths(), new TypeReference<List<Integer>>() {});
            return dueMonths.contains(billingMonth);
        } catch (JsonProcessingException e) {
            log.error("Invalid due_months JSON for fee head {}: {}", feeHead.getCode(), feeHead.getDueMonths());
            return false;
        }
    }

    /**
     * Generate a sequential invoice number: INV-SCHOOLID-XXXXXX
     */
    private synchronized String generateInvoiceNumber(Long schoolId) {
        Optional<String> maxNumber = invoiceRepository.findMaxInvoiceNumber(schoolId);
        long nextSeq = 1;

        if (maxNumber.isPresent()) {
            String max = maxNumber.get();
            String[] parts = max.split("-");
            if (parts.length == 3) {
                try {
                    nextSeq = Long.parseLong(parts[2]) + 1;
                } catch (NumberFormatException ignored) {}
            }
        }

        return String.format("INV-%d-%06d", schoolId, nextSeq);
    }

    /**
     * Issue (finalize) all DRAFT invoices for a session+month.
     * Once issued, invoices are immutable.
     */
    @Transactional
    public int issueInvoices(Long sessionId, Integer billingMonth) {
        Long schoolId = securityUtil.getSchoolId();

        List<Invoice> drafts = invoiceRepository
                .findBySchoolIdAndAcademicSessionIdAndStatus(schoolId, sessionId, InvoiceStatus.DRAFT);

        if (billingMonth != null) {
            drafts = drafts.stream()
                    .filter(i -> i.getBillingMonth() == billingMonth)
                    .collect(Collectors.toList());
        }

        int count = 0;
        for (Invoice invoice : drafts) {
            invoice.setStatus(InvoiceStatus.ISSUED);
            invoice.setIssuedAt(java.time.LocalDateTime.now());
            invoiceRepository.save(invoice);
            count++;
        }

        log.info("Issued {} invoices for session {} month {}", count, sessionId, billingMonth);
        return count;
    }

    /**
     * Mark overdue invoices. Should be called by a scheduler.
     */
    @Transactional
    public int markOverdueInvoices(Long schoolId) {
        AcademicSession session = sessionRepository.findBySchoolIdAndCurrentTrue(schoolId)
                .orElse(null);
        if (session == null) return 0;

        List<Invoice> issued = invoiceRepository
                .findBySchoolIdAndAcademicSessionIdAndStatus(schoolId, session.getId(), InvoiceStatus.ISSUED);

        LocalDate today = LocalDate.now();
        int count = 0;
        for (Invoice invoice : issued) {
            if (invoice.getDueDate().isBefore(today) && invoice.getBalanceDue() > 0) {
                invoice.setStatus(InvoiceStatus.OVERDUE);
                invoiceRepository.save(invoice);
                count++;
            }
        }

        // Also check PARTIALLY_PAID invoices
        List<Invoice> partiallyPaid = invoiceRepository
                .findBySchoolIdAndAcademicSessionIdAndStatus(
                        schoolId, session.getId(), InvoiceStatus.PARTIALLY_PAID);
        for (Invoice invoice : partiallyPaid) {
            if (invoice.getDueDate().isBefore(today) && invoice.getBalanceDue() > 0) {
                invoice.setStatus(InvoiceStatus.OVERDUE);
                invoiceRepository.save(invoice);
                count++;
            }
        }

        return count;
    }
}
