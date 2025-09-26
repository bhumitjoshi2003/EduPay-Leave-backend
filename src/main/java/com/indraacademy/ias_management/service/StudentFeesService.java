package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.FeeStructure;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class StudentFeesService {

    private static final Logger log = LoggerFactory.getLogger(StudentFeesService.class);
    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");

    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private FeeStructureService feeStructureService;
    @Autowired private BusFeesService busFeesService;

    private String getAcademicYear(LocalDate date) {
        Year currentYear = Year.of(date.getYear());
        if (date.getMonthValue() >= 4) {
            return currentYear.format(YEAR_FORMATTER) + "-" +
                    currentYear.plusYears(1).format(YEAR_FORMATTER);
        } else {
            return currentYear.minusYears(1).format(DateTimeFormatter.ofPattern("yyyy")) + "-" +
                    currentYear.format(YEAR_FORMATTER);
        }
    }

    public List<StudentFees> getStudentFees(String studentId, String year) {
        if (studentId == null || studentId.trim().isEmpty() || year == null || year.trim().isEmpty()) {
            log.warn("Attempted to get student fees with null/empty student ID or year.");
            return Collections.emptyList();
        }
        log.info("Fetching student fees for ID: {} and Year: {}", studentId, year);
        try {
            return studentFeesRepository.findByStudentIdAndYearOrderByMonthAsc(studentId, year);
        } catch (DataAccessException e) {
            log.error("Data access error fetching fees for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve student fees due to data access issue.", e);
        }
    }

    public StudentFees updateStudentFees(StudentFees studentFees) {
        if (studentFees == null || studentFees.getId() == null) {
            log.warn("Attempted to update student fees with null object or missing ID.");
            throw new IllegalArgumentException("StudentFees object and ID must be provided for update.");
        }
        log.info("Updating student fees manually for ID: {}", studentFees.getId());

        try {
            studentFees.setManuallyPaid(studentFees.getManuallyPaid());
            studentFees.setManualPaymentReceived(studentFees.getManualPaymentReceived());
            return studentFeesRepository.save(studentFees);
        } catch (DataAccessException e) {
            log.error("Data access error during student fees update for ID: {}", studentFees.getId(), e);
            throw new RuntimeException("Could not update student fees due to data access issue.", e);
        }
    }

    @Transactional
    public void markFeesAsPaid(Payment payment) {
        if (payment == null || payment.getStudentId() == null || payment.getSession() == null || payment.getMonth() == null || payment.getMonth().length() != 12) {
            log.error("Invalid Payment object provided for marking fees as paid.");
            throw new IllegalArgumentException("Payment object must contain valid studentId, session, and a 12-char month string.");
        }

        String studentId = payment.getStudentId();
        String session = payment.getSession();
        String selectedMonths = payment.getMonth();
        String className = payment.getClassName();
        log.info("Marking fees as paid for student ID: {} for session: {}", studentId, session);

        FeeStructure feeStructure;
        try {
            feeStructure = feeStructureService.getFeeStructuresByAcademicYearAndClassName(session, className);
            if (feeStructure == null) {
                log.error("Fee structure not found for session {} and class {}. Cannot calculate and mark fees.", session, className);
                throw new IllegalStateException("Required fee structure data is missing.");
            }
        } catch (Exception e) {
            log.error("Error retrieving FeeStructure for session {} and class {}", session, className, e);
            throw new RuntimeException("Failed to retrieve fee structure data.", e);
        }


        boolean firstMonth = true;
        for (int i = 0; i < 12; i++) {
            if (selectedMonths.charAt(i) == '1') {
                int monthNumber = i + 1;

                try {
                    Optional<StudentFees> optionalStudentFees = Optional.ofNullable(studentFeesRepository.findByStudentIdAndYearAndMonth(studentId, session, monthNumber));

                    if (optionalStudentFees.isPresent()) {
                        StudentFees studentFees = optionalStudentFees.get();

                        double totalAmount = 0.0;
                        totalAmount += feeStructure.getTuitionFee();

                        if(firstMonth){
                            totalAmount += payment.getAdditionalCharges();
                            firstMonth = false;
                        }

                        int lateFee = calculateLateFees(monthNumber);
                        totalAmount += lateFee;

                        if(i == 0) {
                            totalAmount += feeStructure.getLabCharges();
                            totalAmount += feeStructure.getEcaProject();
                            totalAmount += feeStructure.getExaminationFee();
                            totalAmount += feeStructure.getAnnualCharges();
                        }

                        if(studentFees.getTakesBus()){
                            Double distance = studentFees.getDistance();
                            try {
                                BigDecimal busFees = busFeesService.getBusFeesOfDistance(distance, session);
                                if (busFees != null) {
                                    totalAmount += busFees.doubleValue();
                                } else {
                                    log.warn("Bus fees not found for distance {} in session {}. Assuming 0 bus fee.", distance, session);
                                }
                            } catch (Exception e) {
                                log.error("Error retrieving bus fees for student {} month {}", studentId, monthNumber, e);
                            }
                        }

                        studentFees.setPaid(true);
                        studentFees.setManuallyPaid(false);
                        studentFees.setManualPaymentReceived(BigDecimal.valueOf(0));
                        studentFees.setAmountPaid(BigDecimal.valueOf(totalAmount));
                        studentFeesRepository.save(studentFees);
                        log.info("Fees marked as paid for student {} for month {}. Total calculated: {}", studentId, monthNumber, totalAmount);
                    }
                    else{
                        log.error("No StudentFees record found for student {}, session {}, month {}. Cannot mark as paid.", studentId, session, monthNumber);
                    }
                } catch (DataAccessException e) {
                    log.error("Data access error updating fees for student {} month {}.", studentId, monthNumber, e);
                    throw new RuntimeException("Failed to update fees for month " + monthNumber + " due to data access issue.", e);
                } catch (Exception e) {
                    log.error("Unexpected error processing payment for student {} month {}.", studentId, monthNumber, e);
                    throw new RuntimeException("Unexpected error during fee processing for month " + monthNumber, e);
                }
            }
        }
    }

    private int calculateLateFees(int academicFeeMonth) {
        if (academicFeeMonth < 1 || academicFeeMonth > 12) {
            log.warn("Invalid academicFeeMonth: {}. Returning 0 late fees.", academicFeeMonth);
            return 0;
        }

        LocalDate today = LocalDate.now();
        int currentCalendarMonth = today.getMonthValue();
        int academicCurrentMonth = getAcademicMonth(currentCalendarMonth);

        int monthDifference = academicCurrentMonth - academicFeeMonth;

        if (monthDifference <= 0) return 0;

        // Using the same lateFeePerDay logic as in the Angular component
        int[] lateFeePerDay = {12, 15, 18, 21};

        if (monthDifference >= 9) {
            return 30 * lateFeePerDay[3];
        } else if (monthDifference >= 6) {
            return 30 * lateFeePerDay[2];
        } else if (monthDifference >= 3) {
            return 30 * lateFeePerDay[1];
        } else {
            return 30 * lateFeePerDay[0];
        }
    }

    private int getAcademicMonth(int month) {
        return (month >= 4) ? (month - 3) : (month + 9);
    }

    public Optional<StudentFees> getStudentFee(String studentId, String year, Integer month) {
        if (studentId == null || studentId.trim().isEmpty() || year == null || year.trim().isEmpty() || month == null || month < 1 || month > 12) {
            log.warn("Attempted to get student fee with null/empty/invalid parameters. ID: {}, Year: {}, Month: {}", studentId, year, month);
            return Optional.empty();
        }
        log.info("Fetching student fee for ID: {}, Year: {}, Month: {}", studentId, year, month);
        try {
            return Optional.ofNullable(studentFeesRepository.findByStudentIdAndYearAndMonth(studentId, year, month));
        } catch (DataAccessException e) {
            log.error("Data access error fetching single fee record for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve student fee record due to data access issue.", e);
        }
    }

    public StudentFees createStudentFees(StudentFees studentFees) {
        if (studentFees == null || studentFees.getStudentId() == null || studentFees.getYear() == null) {
            log.warn("Attempted to create student fees with null object or missing key fields.");
            throw new IllegalArgumentException("StudentFees object and key fields must be provided for creation.");
        }
        log.info("Creating student fees record for ID: {} Year: {} Month: {}", studentFees.getStudentId(), studentFees.getYear(), studentFees.getMonth());
        try {
            return studentFeesRepository.save(studentFees);
        } catch (DataAccessException e) {
            log.error("Data access error creating student fees for ID: {}", studentFees.getStudentId(), e);
            throw new RuntimeException("Could not create student fees record due to data access issue.", e);
        }
    }

    public List<String> getDistinctYearsByStudentId(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to get distinct years with null/empty student ID.");
            return Collections.emptyList();
        }
        log.info("Fetching distinct years for student ID: {}", studentId);
        try {
            return studentFeesRepository.findDistinctYearsByStudentId(studentId);
        } catch (DataAccessException e) {
            log.error("Data access error fetching distinct years for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve distinct years due to data access issue.", e);
        }
    }

    @Transactional
    public void updateStudentFeesForClassChange(String studentId, String newClassName) {
        if (studentId == null || studentId.trim().isEmpty() || newClassName == null || newClassName.trim().isEmpty()) {
            log.warn("Attempted to update fees for class change with null/empty parameters. ID: {}, New Class: {}", studentId, newClassName);
            throw new IllegalArgumentException("Student ID and new class name must be provided.");
        }

        String academicYear = getAcademicYear(LocalDate.now());
        log.info("Updating class name for student ID: {} to {} in academic year: {}", studentId, newClassName, academicYear);

        try {
            List<StudentFees> studentFeesList = studentFeesRepository.findByStudentIdAndYearOrderByMonthAsc(studentId, academicYear);
            if (studentFeesList.isEmpty()) {
                log.info("No StudentFees records found for student ID: {} in academic year: {}. Skipping update.", studentId, academicYear);
                return;
            }

            int updatedCount = 0;
            for (StudentFees fee : studentFeesList) {
                fee.setClassName(newClassName);
                studentFeesRepository.save(fee);
                updatedCount++;
            }
            log.info("Updated class name for {} StudentFees records for student ID: {}.", updatedCount, studentId);
        } catch (DataAccessException e) {
            log.error("Data access error updating fees for class change for student ID: {}.", studentId, e);
            throw new RuntimeException("Failed to update fees for class change due to data access issue.", e);
        }
    }

    @Transactional
    public void createDefaultStudentFees(String studentId, String className, String year, Boolean takesBus, Double distance) {
        if (studentId == null || studentId.trim().isEmpty() || className == null || className.trim().isEmpty() || year == null || year.trim().isEmpty() || takesBus == null) {
            log.warn("Attempted to create default fees with null/empty parameters. ID: {}, Year: {}", studentId, year);
            throw new IllegalArgumentException("Student ID, Class Name, Year, and Takes Bus status must be provided.");
        }

        log.info("Creating 12 months of default fees for student ID: {} Year: {}", studentId, year);

        try {
            for (int month = 1; month <= 12; month++) {
                StudentFees studentFee = new StudentFees();
                studentFee.setStudentId(studentId);
                studentFee.setClassName(className);
                studentFee.setMonth(month);
                studentFee.setPaid(false);
                studentFee.setTakesBus(takesBus);
                studentFee.setYear(year);
                studentFee.setDistance(Objects.requireNonNullElse(distance, 0.0));
                studentFee.setManuallyPaid(false);
                studentFee.setManualPaymentReceived(null);
                studentFeesRepository.save(studentFee);
            }
            log.info("Successfully created 12 default fee records for student ID: {}", studentId);
        } catch (DataAccessException e) {
            log.error("Data access error creating default student fees for ID: {}.", studentId, e);
            throw new RuntimeException("Failed to create default student fees due to data access issue.", e);
        }
    }

    public void updateStudentBusFees(String studentId, Boolean takesBus, Double distance, Integer effectiveFromMonth) {
        if (studentId == null || studentId.trim().isEmpty() || takesBus == null || effectiveFromMonth == null || effectiveFromMonth < 0 || effectiveFromMonth > 12) {
            log.warn("Attempted to update bus fees with invalid parameters. ID: {}, Month: {}", studentId, effectiveFromMonth);
            throw new IllegalArgumentException("Student ID, Takes Bus status, and a valid Effective Month (0-12) must be provided.");
        }

        if (effectiveFromMonth == 0) {
            log.info("EffectiveFromMonth is 0. Skipping bus fee update for student ID: {}", studentId);
            return;
        }

        String academicYear = getAcademicYear(LocalDate.now());
        log.info("Updating bus fees for student ID: {} starting from academic month {} in year {}", studentId, effectiveFromMonth, academicYear);

        try {
            List<StudentFees> studentFeesList = studentFeesRepository.findByStudentIdAndYearOrderByMonthAsc(studentId, academicYear);

            if (studentFeesList.isEmpty()) {
                log.info("No StudentFees records found for student ID: {} in academic year: {}. Skipping bus fees update.", studentId, academicYear);
                return;
            }

            int updatedCount = 0;
            for (StudentFees fee : studentFeesList) {
                if (fee.getMonth() >= effectiveFromMonth) {
                    log.debug("Updating bus fees for month: {}", fee.getMonth());
                    fee.setTakesBus(takesBus);
                    fee.setDistance(Objects.requireNonNullElse(distance, 0.0));
                    studentFeesRepository.save(fee);
                    updatedCount++;
                }
            }
            log.info("Successfully updated bus fees for {} fee records for student ID: {}.", updatedCount, studentId);
        } catch (DataAccessException e) {
            log.error("Data access error updating bus fees for student ID: {}.", studentId, e);
            throw new RuntimeException("Failed to update student bus fees due to data access issue.", e);
        }
    }
}