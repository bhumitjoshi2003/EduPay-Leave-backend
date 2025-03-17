package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.BusFees;
import com.indraacademy.ias_management.repository.BusFeesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class BusFeesService {

    @Autowired
    private BusFeesRepository busFeesRepository;

    public List<BusFees> getAllRecords() {
        return busFeesRepository.findAll();
    }

    public BigDecimal getBusFeesOfDistance(Double distance, String academicYear) {
        return busFeesRepository.findFeesByDistanceAndAcademicYear(distance, academicYear);
    }

    public List<BusFees> getBusFeesByAcademicYear(String academicYear) {
        return busFeesRepository.findByAcademicYear(academicYear);
    }

    @Transactional
    public List<BusFees> updateBusFees(String academicYear, List<BusFees> updatedFees) {
        List<BusFees> existingFees = busFeesRepository.findByAcademicYear(academicYear);
        busFeesRepository.deleteAll(existingFees);

        List<BusFees> savedFees = new ArrayList<>();
        for (BusFees fee : updatedFees) {
            BusFees newFee = new BusFees();
            newFee.setAcademicYear(academicYear);
            newFee.setMinDistance(fee.getMinDistance());
            newFee.setMaxDistance(fee.getMaxDistance());
            newFee.setFees(fee.getFees());
            savedFees.add(busFeesRepository.save(newFee));
        }
        return savedFees;
    }
}
