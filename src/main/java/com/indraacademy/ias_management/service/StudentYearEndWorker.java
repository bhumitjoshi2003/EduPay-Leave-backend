package com.indraacademy.ias_management.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Gives every submitted student an independent transaction boundary. */
@Service
public class StudentYearEndWorker {
    private final StudentYearEndService yearEndService;

    public StudentYearEndWorker(StudentYearEndService yearEndService) {
        this.yearEndService = yearEndService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StudentYearEndDecision.Result apply(StudentYearEndDecision.Request request) {
        return yearEndService.apply(request);
    }
}
