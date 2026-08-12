package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.exception.SystemBFrozenException;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.FeeHeadRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import com.indraacademy.ias_management.repository.InvoiceRepository;
import com.indraacademy.ias_management.repository.StudentFeeConfigRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * System B freeze phase: none of InvoiceGenerationService's three write methods may create
 * or mutate an Invoice anymore. StudentFees is now the sole canonical fee-generation system.
 * markOverdueInvoices was already unreachable in production (no controller endpoint, no
 * scheduler) before this freeze — guarded anyway for defense in depth.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceGenerationServiceTest {

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private FeeStructureRuleRepository ruleRepository;
    @Mock private FeeHeadRepository feeHeadRepository;
    @Mock private StudentFeeConfigRepository configRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private AcademicSessionRepository sessionRepository;
    @Mock private SecurityUtil securityUtil;

    private InvoiceGenerationService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceGenerationService();
        ReflectionTestUtils.setField(service, "invoiceRepository", invoiceRepository);
        ReflectionTestUtils.setField(service, "ruleRepository", ruleRepository);
        ReflectionTestUtils.setField(service, "feeHeadRepository", feeHeadRepository);
        ReflectionTestUtils.setField(service, "configRepository", configRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
    }

    @Test
    void generateInvoices_rejectedByFreeze_noRepositoryTouched() {
        assertThatThrownBy(() -> service.generateInvoices(1L, 4, "6A", null))
                .isInstanceOf(SystemBFrozenException.class)
                .hasMessageContaining("generating invoices");

        verifyNoInteractions(invoiceRepository, ruleRepository, studentRepository, sessionRepository);
    }

    @Test
    void issueInvoices_rejectedByFreeze_noDraftCanBecomePayable() {
        assertThatThrownBy(() -> service.issueInvoices(1L, null))
                .isInstanceOf(SystemBFrozenException.class)
                .hasMessageContaining("issuing invoices");

        verifyNoInteractions(invoiceRepository);
    }

    @Test
    void markOverdueInvoices_rejectedByFreeze_evenThoughAlreadyUnreachableInProduction() {
        assertThatThrownBy(() -> service.markOverdueInvoices(1L))
                .isInstanceOf(SystemBFrozenException.class)
                .hasMessageContaining("marking invoices overdue");

        verifyNoInteractions(invoiceRepository, sessionRepository);
    }
}
