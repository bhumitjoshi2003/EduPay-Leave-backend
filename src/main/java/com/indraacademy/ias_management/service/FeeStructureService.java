package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.FeeStructure;
import com.indraacademy.ias_management.repository.FeeStructureRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class FeeStructureService {

    private static final Logger log = LoggerFactory.getLogger(FeeStructureService.class);

    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

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
    public List<FeeStructure> updateFeeStructures(String academicYear,
                                                  List<FeeStructure> updatedFees,
                                                  HttpServletRequest request) {

        if (academicYear == null || academicYear.trim().isEmpty()) {
            throw new IllegalArgumentException("Academic year must be provided.");
        }

        if (updatedFees == null) {
            return Collections.emptyList();
        }

        log.info("Updating fee structures for academic year: {}", academicYear);

        try {
            // Capture old state
            List<FeeStructure> existingFees =
                    feeStructureRepository.findByAcademicYear(academicYear);

            String oldValue = objectMapper.writeValueAsString(existingFees);

            if (!existingFees.isEmpty()) {
                feeStructureRepository.deleteAll(existingFees);
            }

            List<FeeStructure> savedFees = new ArrayList<>();

            for (FeeStructure fee : updatedFees) {

                if (fee.getClassName() == null || fee.getClassName().trim().isEmpty()) {
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

            auditService.logUpdate(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "UPDATE_FEE_STRUCTURE",
                    "FeeStructure",
                    academicYear,
                    oldValue,
                    objectMapper.writeValueAsString(savedFees),
                    request.getRemoteAddr()
            );

            return savedFees;

        } catch (DataAccessException e) {
            throw new RuntimeException("Could not update fee structures", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public List<FeeStructure> createNewSession(String academicYear,
                                               List<FeeStructure> newFees,
                                               HttpServletRequest request) {

        if (academicYear == null || academicYear.trim().isEmpty()) {
            throw new IllegalArgumentException("Academic year must be provided.");
        }

        if (newFees == null || newFees.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("Creating new fee session for academic year: {}", academicYear);

        try {
            List<FeeStructure> savedFees = new ArrayList<>();

            for (FeeStructure fee : newFees) {

                if (fee.getClassName() == null || fee.getClassName().trim().isEmpty()) {
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

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "CREATE_FEE_SESSION",
                    "FeeStructure",
                    academicYear,
                    null,
                    objectMapper.writeValueAsString(savedFees),
                    request.getRemoteAddr()
            );

            return savedFees;

        } catch (DataAccessException e) {
            throw new RuntimeException("Could not create new fee session", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}