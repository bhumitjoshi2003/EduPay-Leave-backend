package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.FeeStructure;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class StudentFeesService {

    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private FeeStructureService feeStructureService;
    @Autowired private BusFeesService busFeesService;

    public List<StudentFees> getStudentFees(String studentId, String year) {
        return studentFeesRepository.findByStudentIdAndYearOrderByMonthAsc(studentId, year);
    }

    public StudentFees updateStudentFees(StudentFees studentFees) {
        studentFees.setManuallyPaid(studentFees.getManuallyPaid());
        studentFees.setManualPaymentReceived(studentFees.getManualPaymentReceived());
        return studentFeesRepository.save(studentFees);
    }

    @Transactional
    public void markFeesAsPaid(Payment payment) {
        String studentId = payment.getStudentId();
        String session = payment.getSession();
        String selectedMonths = payment.getMonth();

        FeeStructure feeStructure = feeStructureService.getFeeStructuresByAcademicYearAndClassName(session, payment.getClassName());

        boolean firstMonth = true;
        for (int i = 0; i < 12; i++) {
            if (selectedMonths.charAt(i) == '1') {
                int monthNumber = i + 1;
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
                        BigDecimal busFees = busFeesService.getBusFeesOfDistance(distance, session);
                        if (busFees != null) {
                            totalAmount += busFees.doubleValue();
                        }
                    }

                    studentFees.setPaid(true);
                    studentFees.setManuallyPaid(false);
                    studentFees.setManualPaymentReceived(BigDecimal.valueOf(0));
                    studentFees.setAmountPaid(BigDecimal.valueOf(totalAmount));
                    studentFeesRepository.save(studentFees);
                }
                else{
                    System.err.println("No StudentFees found for student " + studentId + ", session " + session + ", month " + monthNumber);
                }
            }
        }
    }

    private int calculateLateFees(int academicFeeMonth) {
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
        return Optional.ofNullable(studentFeesRepository.findByStudentIdAndYearAndMonth(studentId, year, month));
    }

    public StudentFees createStudentFees(StudentFees studentFees) {
        return studentFeesRepository.save(studentFees);
    }

    public List<String> getDistinctYearsByStudentId(String studentId) {
        return studentFeesRepository.findDistinctYearsByStudentId(studentId);
    }

    @Transactional
    public void updateStudentFeesForClassChange(String studentId, String newClassName) {
        Year currentYear = Year.now();
        LocalDate currentDate = LocalDate.now();
        String academicYear;

        if (currentDate.getMonthValue() >= 4) {
            academicYear = currentYear.format(DateTimeFormatter.ofPattern("yyyy")) + "-" +
                    currentYear.plusYears(1).format(DateTimeFormatter.ofPattern("yyyy"));
        } else {
            academicYear = currentYear.minusYears(1).format(DateTimeFormatter.ofPattern("yyyy")) + "-" +
                    currentYear.format(DateTimeFormatter.ofPattern("yyyy"));
        }

        List<StudentFees> studentFeesList = studentFeesRepository.findByStudentIdAndYearOrderByMonthAsc(studentId, academicYear);
        for (StudentFees fee : studentFeesList) {
            fee.setClassName(newClassName);
            studentFeesRepository.save(fee);
        }
    }

    @Transactional
    public void createDefaultStudentFees(String studentId, String className, String year, Boolean takesBus, Double distance) {
        // Assuming an academic year has 12 months.
        for (int month = 1; month <= 12; month++) {
            StudentFees studentFee = new StudentFees();
            studentFee.setStudentId(studentId);
            studentFee.setClassName(className);
            studentFee.setMonth(month);
            studentFee.setPaid(false);
            studentFee.setTakesBus(takesBus);
            studentFee.setYear(year);
            studentFee.setDistance(distance);
            studentFee.setManuallyPaid(false);
            studentFee.setManualPaymentReceived(null);
            studentFeesRepository.save(studentFee);
        }
    }

    public void updateStudentBusFees(String studentId, Boolean takesBus, Double distance, Integer effectiveFromMonth) {
        if (effectiveFromMonth != null && effectiveFromMonth != 0) {
            Year currentYear = Year.now();
            LocalDate currentDate = LocalDate.now();
            String academicYear;

            if (currentDate.getMonthValue() >= 4) {
                academicYear = currentYear.format(DateTimeFormatter.ofPattern("yyyy")) + "-" +
                        currentYear.plusYears(1).format(DateTimeFormatter.ofPattern("yyyy"));
            } else {
                academicYear = currentYear.minusYears(1).format(DateTimeFormatter.ofPattern("yyyy")) + "-" +
                        currentYear.format(DateTimeFormatter.ofPattern("yyyy"));
            }

            List<StudentFees> studentFeesList = studentFeesRepository.findByStudentIdAndYearOrderByMonthAsc(studentId, academicYear);

            for (StudentFees fee : studentFeesList) {
                if (fee.getMonth() >= effectiveFromMonth) {
                    System.out.println(fee.getMonth());
                    fee.setTakesBus(takesBus);
                    fee.setDistance(distance);
                    studentFeesRepository.save(fee);
                }
            }
        }
    }
}