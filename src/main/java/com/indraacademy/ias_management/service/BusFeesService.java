package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.BusFees;
import com.indraacademy.ias_management.repository.BusFeesRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class BusFeesService {

    private static final Logger log = LoggerFactory.getLogger(BusFeesService.class);

    @Autowired private BusFeesRepository busFeesRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<BusFees> getAllRecords() {
        log.info("Attempting to fetch all bus fees records.");
        // TODO: cache key should incorporate schoolId for multi-tenancy
        try {
            List<BusFees> fees = busFeesRepository.findBySchoolId(securityUtil.getSchoolId());
            log.info("Successfully fetched {} bus fees records.", fees.size());
            return fees;
        } catch (DataAccessException e) {
            log.error("Data access error occurred while fetching all bus fees records.", e);
            throw new RuntimeException("Could not retrieve bus fees records due to data access issue", e);
        }
    }

    @Cacheable(value = "bus-fees", key = "#academicYear + '-' + #distance")
    @Transactional(readOnly = true)
    public BigDecimal getBusFeesOfDistance(Double distance, String academicYear) {
        if (distance == null || academicYear == null || academicYear.trim().isEmpty()) {
            log.warn("Attempted to get bus fees with null distance or empty academic year.");
            return null;
        }
        log.info("Fetching bus fees for distance: {} and academic year: {}", distance, academicYear);

        // TODO: cache key should incorporate schoolId for multi-tenancy
        try {
            BigDecimal fees = busFeesRepository.findFeesByDistanceAndAcademicYearAndSchoolId(distance, academicYear, securityUtil.getSchoolId());
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

    @Cacheable(value = "bus-fees", key = "#academicYear")
    @Transactional(readOnly = true)
    public List<BusFees> getBusFeesByAcademicYear(String academicYear) {
        if (academicYear == null || academicYear.trim().isEmpty()) {
            log.warn("Attempted to get bus fees with null or empty academic year.");
            return Collections.emptyList();
        }
        log.info("Fetching bus fees for academic year: {}", academicYear);

        // TODO: cache key should incorporate schoolId for multi-tenancy
        try {
            List<BusFees> fees = busFeesRepository.findByAcademicYearAndSchoolId(academicYear, securityUtil.getSchoolId());
            log.info("Found {} bus fees records for academic year: {}", fees.size(), academicYear);
            return fees;
        } catch (DataAccessException e) {
            log.error("Data access error fetching bus fees for academic year: {}", academicYear, e);
            throw new RuntimeException("Could not retrieve bus fees by academic year due to data access issue", e);
        }
    }

    @CacheEvict(value = "bus-fees", allEntries = true)
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

        Long schoolId = securityUtil.getSchoolId();
        try {
            // Capture old state before deletion
            List<BusFees> existingFees = busFeesRepository.findByAcademicYearAndSchoolId(academicYear, schoolId);
            String oldValue = objectMapper.writeValueAsString(existingFees);

            if (!existingFees.isEmpty()) {
                busFeesRepository.deleteByAcademicYearAndSchoolId(academicYear, schoolId);
            }

            List<BusFees> savedFees = new ArrayList<>();

            for (BusFees fee : updatedFees) {

                if (fee.getMinDistance() == null ||
                        fee.getFees() == null) {
                    continue;
                }

                BusFees newFee = new BusFees();
                newFee.setAcademicYear(academicYear);
                newFee.setMinDistance(fee.getMinDistance());
                newFee.setMaxDistance(fee.getMaxDistance());
                newFee.setFees(fee.getFees());
                newFee.setSchoolId(schoolId);

                savedFees.add(busFeesRepository.save(newFee));
            }

            // Audit log
            auditService.logUpdate(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "UPDATE_BUS_FEES",
                    "BusFees",
                    academicYear,
                    oldValue,
                    objectMapper.writeValueAsString(savedFees),
                    request.getRemoteAddr()
            );

            log.info("Bus fees updated successfully for academic year: {}", academicYear);

            return savedFees;

        } catch (DataAccessException e) {
            log.error("Data access error during updateBusFees for year: {}", academicYear, e);
            throw new RuntimeException("Could not update bus fees", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}