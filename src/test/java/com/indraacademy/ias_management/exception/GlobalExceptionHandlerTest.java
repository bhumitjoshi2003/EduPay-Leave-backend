package com.indraacademy.ias_management.exception;

import com.indraacademy.ias_management.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HandlerMethodValidationException (raised for e.g. {@code @Valid} on a {@code List<>}
 * {@code @RequestBody}, as used by FeeRuleController.saveRulesForClass) already carries its
 * own 400 status by extending ResponseStatusException — but with no dedicated handler it fell
 * through to the generic Exception.class catch-all and came back as a misleading 500. This
 * reproduces that exact shape (built via the real Spring MethodValidationResult factory, same
 * level of realism as SystemBFreezeExceptionHandlerTest) and confirms the fix.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static class Dummy {
        @SuppressWarnings("unused")
        public void method(String className, Long academicSessionId) {}
    }

    private HandlerMethodValidationException handlerMethodValidationException() throws NoSuchMethodException {
        Method dummyMethod = Dummy.class.getMethod("method", String.class, Long.class);
        Dummy target = new Dummy();

        MethodParameter classNameParam = new MethodParameter(dummyMethod, 0);
        ParameterValidationResult classNameResult = new ParameterValidationResult(
                classNameParam, null,
                List.of(new DefaultMessageSourceResolvable(new String[]{"NotBlank"}, null, "must not be blank")));

        MethodParameter sessionIdParam = new MethodParameter(dummyMethod, 1);
        ParameterValidationResult sessionIdResult = new ParameterValidationResult(
                sessionIdParam, null,
                List.of(new DefaultMessageSourceResolvable(new String[]{"NotNull"}, null, "must not be null")));

        MethodValidationResult validationResult = MethodValidationResult.create(
                target, dummyMethod, List.of(classNameResult, sessionIdResult));

        return new HandlerMethodValidationException(validationResult);
    }

    @Test
    void handlerMethodValidationException_mapsTo400NotGeneric500() throws NoSuchMethodException {
        HandlerMethodValidationException ex = handlerMethodValidationException();

        ResponseEntity<ErrorResponse> response = handler.handleHandlerMethodValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getError()).isEqualTo("Bad Request");
    }

    @Test
    void handlerMethodValidationException_messageIncludesFieldErrors() throws NoSuchMethodException {
        HandlerMethodValidationException ex = handlerMethodValidationException();

        ResponseEntity<ErrorResponse> response = handler.handleHandlerMethodValidation(ex);

        assertThat(response.getBody().getMessage())
                .contains("must not be blank")
                .contains("must not be null");
    }

    @Test
    void handlerMethodValidationException_neverFallsThroughToGenericHandler() throws NoSuchMethodException {
        // Confirms the specific handler — not handleGeneric — is what a real dispatch would
        // pick, by checking the generic handler would have produced a different (500) result.
        HandlerMethodValidationException ex = handlerMethodValidationException();

        ResponseEntity<ErrorResponse> specific = handler.handleHandlerMethodValidation(ex);
        ResponseEntity<ErrorResponse> generic = handler.handleGeneric(ex);

        assertThat(specific.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(generic.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void illegalArgument_stillMapsTo400_unaffectedByThisChange() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(new IllegalArgumentException("bad input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("bad input");
    }

    @Test
    void noSuchElement_stillMapsTo404_unaffectedByThisChange() {
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(new NoSuchElementException("not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getStatus()).isEqualTo(404);
    }

    /**
     * Same failure shape as the HandlerMethodValidationException case above, found live while
     * testing teacher/class authorization: an ADMIN calling a {@code @PreAuthorize("hasRole('TEACHER')")}
     * endpoint was correctly denied, but the response said 500 "An unexpected error occurred."
     * rather than 403 — indistinguishable, to a client, from the server actually being broken.
     */
    @Test
    void accessDenied_mapsTo403_notTheGeneric500() {
        AuthorizationDeniedException ex = new AuthorizationDeniedException("Access Denied");

        ResponseEntity<ErrorResponse> specific = handler.handleAccessDenied(ex);
        ResponseEntity<ErrorResponse> generic = handler.handleGeneric(ex);

        assertThat(specific.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(specific.getBody().getStatus()).isEqualTo(403);
        // Without the dedicated handler this is what callers actually got:
        assertThat(generic.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** The denial reason is never echoed back — it can name internal roles/expressions. */
    @Test
    void accessDenied_doesNotLeakTheInternalDenialMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDenied(new AuthorizationDeniedException("hasRole('SUPER_ADMIN') failed"));

        assertThat(response.getBody().getMessage()).doesNotContain("SUPER_ADMIN");
    }

    /**
     * Backstop for LeaveService.updateLeaveStatus's same-status guard, for any future caller that
     * invokes it without its own local catch (LeaveController and LeaveDecisionService already
     * have one — see their own tests). Same reasoning as the AccessDeniedException case above:
     * without a dedicated handler this falls through to handleGeneric and reports a routine,
     * expected conflict as a 500.
     */
    @Test
    void invalidLeaveStatusTransition_mapsTo409_notTheGeneric500() {
        InvalidLeaveStatusTransitionException ex =
                new InvalidLeaveStatusTransitionException(42L, com.indraacademy.ias_management.entity.LeaveStatus.APPROVED,
                        com.indraacademy.ias_management.entity.LeaveStatus.APPROVED);

        ResponseEntity<ErrorResponse> specific = handler.handleInvalidLeaveStatusTransition(ex);
        ResponseEntity<ErrorResponse> generic = handler.handleGeneric(ex);

        assertThat(specific.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(specific.getBody().getStatus()).isEqualTo(409);
        assertThat(specific.getBody().getMessage()).contains("42").contains("APPROVED");
        // Without the dedicated handler this is what callers actually got:
        assertThat(generic.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Same failure shape as the other cases above — found live during the teacher-production-
     * readiness audit: ReportCardController's new TEACHER class-ownership checks throw
     * {@code ResponseStatusException(FORBIDDEN, ...)} directly (the same exception type the
     * pre-existing STUDENT-only-published-report-card check in that same controller already
     * used), but with no dedicated handler for the base type it fell through to handleGeneric
     * and came back as a 500 — masking a routine, correctly-detected 403 as a server failure.
     */
    @Test
    void responseStatusException_mapsToItsOwnStatus_notTheGeneric500() {
        org.springframework.web.server.ResponseStatusException ex =
                new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Teachers can only access report card data for their own class.");

        ResponseEntity<ErrorResponse> specific = handler.handleResponseStatusException(ex);
        ResponseEntity<ErrorResponse> generic = handler.handleGeneric(ex);

        assertThat(specific.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(specific.getBody().getStatus()).isEqualTo(403);
        assertThat(specific.getBody().getMessage()).isEqualTo("Teachers can only access report card data for their own class.");
        // Without the dedicated handler this is what callers actually got:
        assertThat(generic.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void responseStatusException_withDifferentStatus_mapsCorrectly() {
        org.springframework.web.server.ResponseStatusException ex =
                new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: 1");

        ResponseEntity<ErrorResponse> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo("Template not found: 1");
    }
}
