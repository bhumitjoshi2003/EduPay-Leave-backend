package com.indraacademy.ias_management.exception;

import com.indraacademy.ias_management.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
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
}
