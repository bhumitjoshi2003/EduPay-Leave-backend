package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.BusFees;
import com.indraacademy.ias_management.repository.BusFeesRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class BusFeesService {

    private static final Logger log = LoggerFactory.getLogger(BusFeesService.class);

    @Autowired
    private BusFeesRepository busFeesRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private SecurityUtil securityUtil;

    public List<BusFees> getAllRecords() {
        log.info("Attempting to fetch all bus fees records.");
        try {
            List<BusFees> fees = busFeesRepository.findAll();
            log.info("Successfully fetched {} bus fees records.", fees.size());
            return fees;
        } catch (DataAccessException e) {
            log.error("Data access error occurred while fetching all bus fees records.", e);
            throw new RuntimeException("Could not retrieve bus fees records due to data access issue", e);
        }
    }

    public BigDecimal getBusFeesOfDistance(Double distance, String academicYear) {
        if (distance == null || academicYear == null || academicYear.trim().isEmpty()) {
            log.warn("Attempted to get bus fees with null distance or empty academic year.");
            return null;
        }
        log.info("Fetching bus fees for distance: {} and academic year: {}", distance, academicYear);

        try {
            BigDecimal fees = busFeesRepository.findFeesByDistanceAndAcademicYear(distance, academicYear);
            if (fees != null) {
                log.info("Found bus fees: {} for distance: {}", fees, distance);
            } else {
                log.warn("Bus fees not found for distance: {} and academic year: {}", distance, academicYear);
            }
            return fees;
        } catch (DataAccessException e) {
            log.error("Data access error fetching bus fees for distance {} and academic year {}", distance, academicYear, e);
            throw new RuntimeException("Could not retrieve bus fees due to data access issue", e);
        }
    }

    public List<BusFees> getBusFeesByAcademicYear(String academicYear) {
        if (academicYear == null || academicYear.trim().isEmpty()) {
            log.warn("Attempted to get bus fees with null or empty academic year.");
            return Collections.emptyList();
        }
        log.info("Fetching bus fees for academic year: {}", academicYear);

        try {
            List<BusFees> fees = busFeesRepository.findByAcademicYear(academicYear);
            log.info("Found {} bus fees records for academic year: {}", fees.size(), academicYear);
            return fees;
        } catch (DataAccessException e) {
            log.error("Data access error fetching bus fees for academic year: {}", academicYear, e);
            throw new RuntimeException("Could not retrieve bus fees by academic year due to data access issue", e);
        }
    }

    @Transactional
    public List<BusFees> updateBusFees(String academicYear,
                                       List<BusFees> updatedFees,
                                       HttpServletRequest request) {

        if (academicYear == null || academicYear.trim().isEmpty()) {
            throw new IllegalArgumentException("Academic year must not be null or empty.");
        }

        if (updatedFees == null) {
            return Collections.emptyList();
        }

        log.info("Updating bus fees for academic year: {}", academicYear);

        try {
            // Capture old state before deletion
            List<BusFees> existingFees = busFeesRepository.findByAcademicYear(academicYear);
            String oldValue = existingFees.toString();

            if (!existingFees.isEmpty()) {
                busFeesRepository.deleteAll(existingFees);
            }

            List<BusFees> savedFees = new ArrayList<>();

            for (BusFees fee : updatedFees) {

                if (fee.getMinDistance() == null ||
                        fee.getMaxDistance() == null ||
                        fee.getFees() == null) {
                    continue;
                }

                BusFees newFee = new BusFees();
                newFee.setAcademicYear(academicYear);
                newFee.setMinDistance(fee.getMinDistance());
                newFee.setMaxDistance(fee.getMaxDistance());
                newFee.setFees(fee.getFees());

                savedFees.add(busFeesRepository.save(newFee));
            }

            // Audit log
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "UPDATE_BUS_FEES",
                    "BusFees",
                    academicYear,
                    oldValue,
                    savedFees.toString(),
                    request.getRemoteAddr()
            );

            log.info("Bus fees updated successfully for academic year: {}", academicYear);

            return savedFees;

        } catch (DataAccessException e) {
            log.error("Data access error during updateBusFees for year: {}", academicYear, e);
            throw new RuntimeException("Could not update bus fees", e);
        }
    }
}