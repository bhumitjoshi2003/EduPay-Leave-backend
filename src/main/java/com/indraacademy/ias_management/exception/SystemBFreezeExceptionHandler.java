package com.indraacademy.ias_management.exception;

import com.indraacademy.ias_management.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 423 LOCKED — the Invoice/FeePayment write path being called is frozen. See
 * SystemBFrozenException for the architecture decision this enforces. */
@RestControllerAdvice
public class SystemBFreezeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SystemBFreezeExceptionHandler.class);

    @ExceptionHandler(SystemBFrozenException.class)
    public ResponseEntity<ErrorResponse> handleSystemBFrozen(SystemBFrozenException ex) {
        log.warn("Rejected System B financial write: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.LOCKED)
                .body(new ErrorResponse(423, "Locked", ex.getMessage()));
    }
}
