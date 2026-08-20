package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.LineItemType;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import com.indraacademy.ias_management.entity.StudentOneTimeFeeCharged;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.SchoolFeeSettings;
import com.indraacademy.ias_management.entity.FeeOperationalStatus;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesLineItemRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentOneTimeFeeChargedRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.SchoolFeeSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StudentFeesGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StudentFeesGenerationService.class);

    private static final List<String> DEFAULT_CLASS_SEQUENCE = List.of(
            "Play Group", "Nursery", "LKG", "UKG",
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"
    );

    @Autowired private StudentRepository studentRepository;
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private AuditService auditService;
    @Autowired private FeeCalculationService feeCalculationService;
    @Autowired private StudentOneTimeFeeChargedRepository studentOneTimeFeeChargedRepository;
    @Autowired private StudentFeesLineItemRepository studentFeesLineItemRepository;
    @Autowired private SchoolFeeSettingsRepository schoolFeeSettingsRepository;

    /**
     * Runs on the 1st of every month. For each active school, checks whether this
     * month is the month BEFORE their academic year starts (the "generation month").
     * If so, generates fee records for all returning students for the next academic year.
     *
     * Examples:
     *   April-start school  → generates on March 1
     *   July-start school   → generates on June 1
     *   January-start school → generates on December 1
     */
    @Transactional
    @Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Kolkata")
    public void generateStudentFeesForNextYear() {
        LocalDate today = LocalDate.now();
        log.info("Monthly fee-generation check triggered for date: {}", today);

        List<School> activeSchools = schoolRepository.findAll().stream()
                .filter(School::isActive)
                .collect(Collectors.toList());

        // Load all active students once, grouped by school to avoid N+1 queries
        Map<Long, List<Student>> studentsBySchool = studentRepository.findByStatus(StudentStatus.ACTIVE)
                .stream()
                .collect(Collectors.groupingBy(Student::getSchoolId));

        for (School school : activeSchools) {
            try {
                // Legacy schools with no workflow settings retain the old scheduler behaviour.
                // Once a school adopts the workflow, annual generation is opt-in and only runs
                // while fees are operationally ACTIVE.
                Optional<SchoolFeeSettings> feeSettings = schoolFeeSettingsRepository.findBySchoolId(school.getId());
                if (feeSettings.isPresent()
                        && (feeSettings.get().getOperationalStatus() != FeeOperationalStatus.ACTIVE
                        || !feeSettings.get().isAutomaticAnnualGeneration())) {
                    log.debug("Skipping annual fee generation for school {}: workflow status={}, automatic={}",
                            school.getId(), feeSettings.get().getOperationalStatus(),
                            feeSettings.get().isAutomaticAnnualGeneration());
                    continue;
                }
                generateForSchool(school, today, studentsBySchool.getOrDefault(school.getId(), List.of()));
            } catch (DataAccessException e) {
                log.error("DB error generating fees for school {}", school.getId(), e);
            } catch (Exception e) {
                log.error("Unexpected error generating fees for school {}", school.getId(), e);
            }
        }
    }

    /**
     * The per-school generation body, extracted so it's directly unit-testable with a
     * controlled `today` — generateStudentFeesForNextYear() (the @Scheduled entry point)
     * can't itself be driven deterministically since it reads LocalDate.now() and every
     * active school/student from the database. Package-private for tests.
     */
    void generateForSchool(School school, LocalDate today, List<Student> students) {
        int startMonth = school.getAcademicYearStartMonth();
        // Generation month = the calendar month immediately before the academic year starts
        int generationMonth = ((startMonth - 2 + 12) % 12) + 1;

        if (today.getMonthValue() != generationMonth) return;

        // Compute next academic year label
        // For non-January starts: next year begins in startMonth of THIS calendar year
        // For January start: next year begins in January of NEXT calendar year
        int nextStartYear = (startMonth > today.getMonthValue()) ? today.getYear() : today.getYear() + 1;
        String nextAcademicYear = nextStartYear + "-" + (nextStartYear + 1);

        LocalDate currentAcademicYearStart = computeCurrentAcademicYearStart(today, startMonth);

        log.info("School {} generating fees for academic year {}", school.getId(), nextAcademicYear);

        List<String> classSequence = getSchoolClassSequence(school.getId());

        int feesGeneratedCount = 0;
        int configInvalidCount = 0;
        for (Student student : students) {
            // Skip students enrolled during the current ACADEMIC year (see
            // currentAcademicYearStart above) — their fees are generated at
            // registration time, not here.
            if (student.getCreatedAt() != null
                    && !student.getCreatedAt().toLocalDate().isBefore(currentAcademicYearStart)) {
                log.debug("Skipping new enrollment: student {}", student.getStudentId());
                continue;
            }

            String nextClass = determineNextClass(student.getClassName(), classSequence);
            if (nextClass == null) {
                log.info("Skipping graduating student {} (class {})", student.getStudentId(), student.getClassName());
                continue;
            }

            // Skip if fees already generated (guard against double-runs)
            if (studentFeesRepository.existsByStudentIdAndYearAndSchoolId(
                    student.getStudentId(), nextAcademicYear, school.getId())) {
                log.debug("Fees already generated for student {} year {}", student.getStudentId(), nextAcademicYear);
                continue;
            }

            // Never persist a fee snapshot when we cannot confidently calculate it — validated
            // ONCE, BEFORE any row for this student is created, so an unconfigured class never
            // leaves a partially-generated (or fully ₹0.00) financial record behind. This is a
            // structural check (session + at least one rule ever configured), not a per-month
            // one — see FeeCalculationService.SnapshotStatus for the narrower per-month signal.
            FeeCalculationService.FeeConfigurationStatus configStatus =
                    feeCalculationService.validateFeeConfiguration(school.getId(), nextAcademicYear, nextClass);
            if (!configStatus.valid()) {
                log.error("Skipping fee generation for student {} — schoolId={}, session={}, className={}: {}",
                        student.getStudentId(), school.getId(), nextAcademicYear, nextClass, configStatus.reason());
                configInvalidCount++;
                continue;
            }

            // A continuing student's fees for next year are never their "first row" —
            // that only ever applies to a brand-new mid-session admission (see
            // StudentFeesService.createDefaultStudentFees). Every month here uses the
            // normal school-wide schedule (appliesThisAcademicMonth), not appliesAtJoin.
            Set<Long> chargedOneTimeFeeHeadIds = new HashSet<>(
                    studentOneTimeFeeChargedRepository.findFeeHeadIdBySchoolIdAndStudentId(
                            school.getId(), student.getStudentId()));

            for (int month = 1; month <= 12; month++) {
                LocalDate asOfDate = feeCalculationService.academicMonthStart(
                        month, nextStartYear, nextStartYear + 1, startMonth);

                FeeCalculationService.MonthSnapshot snapshot = feeCalculationService.computeMonthSnapshot(
                        school.getId(), nextAcademicYear, nextClass, student.getStudentId(),
                        month, false, asOfDate, student.getTakesBus(), student.getDistance(),
                        chargedOneTimeFeeHeadIds);

                StudentFees studentFees = new StudentFees();
                studentFees.setStudentId(student.getStudentId());
                studentFees.setClassName(nextClass);
                studentFees.setMonth(month);
                studentFees.setPaid(false);
                studentFees.setTakesBus(student.getTakesBus());
                studentFees.setYear(nextAcademicYear);
                studentFees.setDistance(student.getDistance());
                studentFees.setManuallyPaid(false);
                studentFees.setManualPaymentReceived(null);
                studentFees.setSchoolId(school.getId());
                studentFees.setBaseAmountDue(snapshot.baseAmountDue());
                studentFees.setBusFeeDue(snapshot.busFeeDue());
                studentFees.setDiscountAmount(snapshot.discountAmount());
                studentFees.setAmountComputedAt(LocalDateTime.now());
                studentFees.setAmountRuleSnapshot(snapshot.ruleSnapshotJson());
                studentFees.setSnapshotStatus(snapshot.status());
                studentFeesRepository.save(studentFees);

                for (FeeCalculationService.LineItemSnapshot li : snapshot.lineItems()) {
                    StudentFeesLineItem lineItem = new StudentFeesLineItem();
                    lineItem.setStudentFeesId(studentFees.getId());
                    lineItem.setSchoolId(school.getId());
                    lineItem.setStudentId(student.getStudentId());
                    lineItem.setSession(nextAcademicYear);
                    lineItem.setMonth(month);
                    lineItem.setLineItemType(LineItemType.valueOf(li.lineItemType()));
                    lineItem.setFeeHeadId(li.feeHeadId());
                    lineItem.setFeeHeadCode(li.feeHeadCode());
                    lineItem.setFeeHeadName(li.feeHeadName());
                    lineItem.setFrequency(li.frequency());
                    lineItem.setGrossAmountPaise(li.grossPaise());
                    lineItem.setDiscountAmountPaise(li.discountPaise());
                    lineItem.setNetAmountPaise(li.netPaise());
                    lineItem.setDiscountConfigType(li.discountConfigType());
                    studentFeesLineItemRepository.save(lineItem);
                }

                for (Long feeHeadId : snapshot.newlyChargedOneTimeFeeHeadIds()) {
                    studentOneTimeFeeChargedRepository.save(
                            new StudentOneTimeFeeCharged(school.getId(), student.getStudentId(), feeHeadId));
                    chargedOneTimeFeeHeadIds.add(feeHeadId);
                }
            }

            log.debug("Generated 12 months of fees for student {} (class {}) for year {}",
                    student.getStudentId(), nextClass, nextAcademicYear);
            feesGeneratedCount++;
        }

        auditService.log(
                "SYSTEM", "SYSTEM",
                "GENERATE_STUDENT_FEES",
                "StudentFees",
                nextAcademicYear,
                null,
                "School " + school.getId() + ": generated fees for " + feesGeneratedCount + " students"
                        + (configInvalidCount > 0 ? "; skipped " + configInvalidCount + " due to invalid/missing fee configuration" : ""),
                "SYSTEM"
        );

        log.info("School {}: fee generation complete — {} students for year {}",
                school.getId(), feesGeneratedCount, nextAcademicYear);
    }

    /**
     * Calendar date the CURRENTLY-running academic year started, for a school with the given
     * start month, as of `today`. Used to skip students who enrolled during it (their fees for
     * that year were already generated at registration time by
     * StudentFeesService.createDefaultStudentFees; they're picked up by this same job next
     * cycle instead, once they're no longer this year's new enrollees).
     *
     * Deriving this from the school's real startMonth (not calendar-year equality) matters:
     * the previous `createdAt.getYear() >= today.getYear()` check only worked for a
     * January-start school — for any other start month it left continuing students wrongly
     * skipped for a window of `startMonth - 1` calendar months (3 months for the April
     * default, up to 11 months for a December-start school).
     *
     * Package-private (not private) so it's directly unit-testable without needing to control
     * LocalDate.now() inside generateStudentFeesForNextYear() itself.
     */
    LocalDate computeCurrentAcademicYearStart(LocalDate today, int startMonth) {
        return (today.getMonthValue() >= startMonth)
                ? LocalDate.of(today.getYear(), startMonth, 1)
                : LocalDate.of(today.getYear() - 1, startMonth, 1);
    }

    private List<String> getSchoolClassSequence(Long schoolId) {
        List<String> classes = schoolClassRepository
                .findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true)
                .stream()
                .map(c -> c.getName())
                .collect(Collectors.toList());
        return classes.isEmpty() ? DEFAULT_CLASS_SEQUENCE : classes;
    }

    private String determineNextClass(String currentClass, List<String> classSequence) {
        if (currentClass == null || currentClass.trim().isEmpty()) return null;
        for (int i = 0; i < classSequence.size(); i++) {
            if (classSequence.get(i).equalsIgnoreCase(currentClass)) {
                return (i == classSequence.size() - 1) ? null : classSequence.get(i + 1);
            }
        }
        log.warn("Class '{}' not found in school's class sequence", currentClass);
        return null;
    }
}
