package com.indraacademy.ias_management.dto;

import java.math.BigDecimal;

/** Backend-authoritative resolution of a single student's bus fee — never recomputed on the frontend. */
public record ApplicableBusFeeDto(
        String studentId,
        boolean takesBus,
        Double distance,
        String academicYear,
        BigDecimal busFee) {}
