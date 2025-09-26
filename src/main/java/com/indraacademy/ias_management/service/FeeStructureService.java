package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.FeeStructure;
import com.indraacademy.ias_management.repository.FeeStructureRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class FeeStructureService {

    private static final Logger log = LoggerFactory.getLogger(FeeStructureService.class);

    @Autowired
    private FeeStructureRepository feeStructureRepository;

    public List<FeeStructure> getAllRecords() {
        log.info("Attempting to fetch all fee structure records.");
        try {
            List<FeeStructure> fees = feeStructureRepository.findAll();
            log.info("Successfully fetched {} fee structure records.", fees.size());
            return fees;
        } catch (DataAccessException e) {
            log.error("Data access error occurred while fetching all fee structure records.", e);
            throw new RuntimeException("Could not retrieve fee structure records due to data access issue", e);
        }
    }

    public List<FeeStructure> getFeeStructuresByAcademicYear(String academicYear) {
        if (academicYear == null || academicYear.trim().isEmpty()) {
            log.warn("Attempted to fetch fee structures with null or empty academic year.");
            return Collections.emptyList();
        }
        log.info("Fetching fee structures for academic year: {}", academicYear);
        try {
            List<FeeStructure> fees = feeStructureRepository.findByAcademicYear(academicYear);
            log.info("Found {} fee structure records for academic year: {}", fees.size(), academicYear);
            return fees;
        } catch (DataAccessException e) {
            log.error("Data access error fetching fee structures for academic year: {}", academicYear, e);
            throw new RuntimeException("Could not retrieve fee structures due to data access issue", e);
        }
    }

    public FeeStructure getFeeStructuresByAcademicYearAndClassName(String academicYear, String className) {
        if (academicYear == null || academicYear.trim().isEmpty() || className == null || className.trim().isEmpty()) {
            log.warn("Attempted to fetch fee structure with null/empty academic year or class name.");
            return null;
        }
        log.info("Fetching fee structure for academic year: {} and class: {}", academicYear, className);
        try {
            FeeStructure fee = feeStructureRepository.findByAcademicYearAndClassName(academicYear, className);
            if (fee == null) {
                log.warn("Fee structure not found for year: {} and class: {}", academicYear, className);
            } else {
                log.info("Fee structure found for year: {} and class: {}", academicYear, className);
            }
            return fee;
        } catch (DataAccessException e) {
            log.error("Data access error fetching fee structure for year {} and class {}", academicYear, className, e);
            throw new RuntimeException("Could not retrieve fee structure due to data access issue", e);
        }
    }

    @Transactional
    public List<FeeStructure> updateFeeStructures(String academicYear, List<FeeStructure> updatedFees) {
        if (academicYear == null || academicYear.trim().isEmpty()) {
            log.error("Cannot update fee structures: Academic year is null or empty.");
            throw new IllegalArgumentException("Academic year must be provided.");
        }
        if (updatedFees == null || updatedFees.isEmpty()) {
            log.warn("Attempted to update fee structures for year {} with null or empty list. No action taken.", academicYear);
            return Collections.emptyList();
        }
        log.info("Starting transactional update of fee structures for academic year: {}. {} new fee structures provided.", academicYear, updatedFees.size());

        try {
            List<FeeStructure> existingFees = feeStructureRepository.findByAcademicYear(academicYear);
            if (!existingFees.isEmpty()) {
                log.debug("Deleting {} existing fee structure records for year: {}", existingFees.size(), academicYear);
                feeStructureRepository.deleteAll(existingFees);
            }

            List<FeeStructure> savedFees = new ArrayList<>();
            for (FeeStructure fee : updatedFees) {
                if (fee.getClassName() == null || fee.getClassName().trim().isEmpty()) {
                    log.warn("Skipping fee entry due to null or empty class name.");
                    continue;
                }
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
            log.info("Successfully saved {} new fee structure records for academic year: {}", savedFees.size(), academicYear);
            return savedFees;
        } catch (DataAccessException e) {
            log.error("Data access error during updateFeeStructures for academic year: {}", academicYear, e);
            throw new RuntimeException("Could not update fee structures due to data access issue", e);
        }
    }

    @Transactional
    public List<FeeStructure> createNewSession(String academicYear, List<FeeStructure> newFees){
        if (academicYear == null || academicYear.trim().isEmpty()) {
            log.error("Cannot create new session: Academic year is null or empty.");
            throw new IllegalArgumentException("Academic year must be provided.");
        }
        if (newFees == null || newFees.isEmpty()) {
            log.warn("Attempted to create new session for year {} with null or empty list. No action taken.", academicYear);
            return Collections.emptyList();
        }
        log.info("Starting transactional creation of new fee session for academic year: {}. {} new fee structures provided.", academicYear, newFees.size());

        try {
            List<FeeStructure> savedFees = new ArrayList<>();
            for (FeeStructure fee : newFees) {
                if (fee.getClassName() == null || fee.getClassName().trim().isEmpty()) {
                    log.warn("Skipping fee entry due to null or empty class name.");
                    continue;
                }
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
            log.info("Successfully created {} fee structure records for new academic year: {}", savedFees.size(), academicYear);
            return savedFees;
        } catch (DataAccessException e) {
            log.error("Data access error during createNewSession for academic year: {}", academicYear, e);
            throw new RuntimeException("Could not create new fee session due to data access issue", e);
        }
    }
}