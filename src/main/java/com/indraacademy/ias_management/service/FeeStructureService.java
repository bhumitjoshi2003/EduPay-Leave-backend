package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.FeeStructure;
import com.indraacademy.ias_management.repository.FeeStructureRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class FeeStructureService {

    @Autowired
    private FeeStructureRepository feeStructureRepository;

    public List<FeeStructure> getAllRecords() {
        return feeStructureRepository.findAll();
    }

    public List<FeeStructure> getFeeStructuresByAcademicYear(String academicYear) {
        return feeStructureRepository.findByAcademicYear(academicYear);
    }

    public FeeStructure getFeeStructuresByAcademicYearAndClassName(String academicYear, String className) {
        return feeStructureRepository.findByAcademicYearAndClassName(academicYear, className);
    }

    @Transactional
    public List<FeeStructure> updateFeeStructures(String academicYear, List<FeeStructure> updatedFees) {
        List<FeeStructure> existingFees = feeStructureRepository.findByAcademicYear(academicYear);
        feeStructureRepository.deleteAll(existingFees);

        List<FeeStructure> savedFees = new ArrayList<>();
        for (FeeStructure fee : updatedFees) {
            FeeStructure newFee = new FeeStructure();
            newFee.setAcademicYear(academicYear);
            newFee.setClassName(fee.getClassName());
            newFee.setTuitionFee(fee.getTuitionFee());
            newFee.setAdmissionFee(fee.getAdmissionFee());
            newFee.setAnnualCharges(fee.getAnnualCharges());
            newFee.setEcaProject(fee.getEcaProject());
            newFee.setExaminationFee(fee.getExaminationFee());
            newFee.setLabCharges(fee.getLabCharges());
            savedFees.add(feeStructureRepository.save(newFee));
        }
        return savedFees;
    }

    @Transactional
    public List<FeeStructure> createNewSession(String academicYear, List<FeeStructure> newFees){
        List<FeeStructure> savedFees = new ArrayList<>();
        for (FeeStructure fee : newFees) {
            FeeStructure newFee = new FeeStructure();
            newFee.setAcademicYear(academicYear);
            newFee.setClassName(fee.getClassName());
            newFee.setTuitionFee(fee.getTuitionFee());
            newFee.setAdmissionFee(fee.getAdmissionFee());
            newFee.setAnnualCharges(fee.getAnnualCharges());
            newFee.setEcaProject(fee.getEcaProject());
            newFee.setExaminationFee(fee.getExaminationFee());
            newFee.setLabCharges(fee.getLabCharges());
            savedFees.add(feeStructureRepository.save(newFee));
        }
        return savedFees;
    }
}