package com.indraacademy.ias_management.exception;

import com.indraacademy.ias_management.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** Confirms a frozen System B write path returns a clear, structured 423 rather than a bare
 * 500 or a silently-swallowed failure — the explicit "clear response/error" requirement. */
class SystemBFreezeExceptionHandlerTest {

    @Test
    void frozenException_mapsTo423WithClearMessage() {
        SystemBFreezeExceptionHandler handler = new SystemBFreezeExceptionHandler();
        SystemBFrozenException ex = new SystemBFrozenException("recording a fee payment");

        ResponseEntity<ErrorResponse> response = handler.handleSystemBFrozen(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(423);
        assertThat(response.getBody().getError()).isEqualTo("Locked");
        assertThat(response.getBody().getMessage())
                .contains("recording a fee payment")
                .contains("StudentFees");
    }
}
