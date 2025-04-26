package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class StudentFeesService {

    @Autowired
    private StudentFeesRepository studentFeesRepository;

    public List<StudentFees> getStudentFees(String studentId, String year) {
        return studentFeesRepository.findByStudentIdAndYear(studentId, year);
    }

    public StudentFees updateStudentFees(StudentFees studentFees) {
        studentFees.setManuallyPaid(studentFees.getManuallyPaid());
        studentFees.setManualPaymentReceived(studentFees.getManualPaymentReceived());
        return studentFeesRepository.save(studentFees);
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

        List<StudentFees> studentFeesList = studentFeesRepository.findByStudentIdAndYear(studentId, academicYear);
        for (StudentFees fee : studentFeesList) {
            fee.setClassName(newClassName);
            studentFeesRepository.save(fee);
        }
    }

    /**
     * Creates default StudentFees entries for a student for an entire academic year.
     * @param studentId The ID of the student.
     * @param className The class name of the student.
     * @param year The academic year.
     * @param takesBus Whether the student takes the bus.
     * @param distance The distance the student travels by bus.
     */
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

            List<StudentFees> studentFeesList = studentFeesRepository.findByStudentIdAndYear(studentId, academicYear);

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