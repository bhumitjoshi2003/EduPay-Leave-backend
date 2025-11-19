package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.BusFees;
import com.indraacademy.ias_management.repository.BusFeesRepository;
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
    public List<BusFees> updateBusFees(String academicYear, List<BusFees> updatedFees) {
        if (academicYear == null || academicYear.trim().isEmpty()) {
            log.error("Cannot update bus fees. Academic year is null or empty.");
            throw new IllegalArgumentException("Academic year must not be null or empty for update operation.");
        }
        if (updatedFees == null) {
            log.warn("Attempted to update bus fees for year {} with null value. No action taken.", academicYear);
            return Collections.emptyList();
        }
        log.info("Starting transactional update of bus fees for academic year: {}. {} new fee structures provided.", academicYear, updatedFees.size());

        try {
            List<BusFees> existingFees = busFeesRepository.findByAcademicYear(academicYear);
            if (!existingFees.isEmpty()) {
                log.debug("Deleting {} existing bus fees records for year: {}", existingFees.size(), academicYear);
                busFeesRepository.deleteAll(existingFees);
            } else {
                log.debug("No existing bus fees records found for year: {}", academicYear);
            }

            List<BusFees> savedFees = new ArrayList<>();
            for (BusFees fee : updatedFees) {
                BusFees newFee = new BusFees();
                newFee.setAcademicYear(academicYear);

                if (fee.getMinDistance() == null || fee.getMaxDistance() == null || fee.getFees() == null) {
                    log.warn("Skipping fee entry due to null value in distance or fees: Min: {}, Max: {}, Fees: {}",
                            fee.getMinDistance(), fee.getMaxDistance(), fee.getFees());
                    continue;
                }

                newFee.setMinDistance(fee.getMinDistance());
                newFee.setMaxDistance(fee.getMaxDistance());
                newFee.setFees(fee.getFees());

                savedFees.add(busFeesRepository.save(newFee));
            }
            log.info("Successfully saved {} new bus fees records for academic year: {}", savedFees.size(), academicYear);
            return savedFees;
        } catch (DataAccessException e) {
            log.error("Data access error occurred during updateBusFees for academic year: {}", academicYear, e);
            throw new RuntimeException("Could not update bus fees due to data access issue", e);
        }
    }
}