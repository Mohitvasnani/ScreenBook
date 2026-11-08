package com.mohit.screenbook.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Turns exceptions into RFC 9457 problem+json. Extending
 * ResponseEntityExceptionHandler means Spring's own failures, such as
 * unparseable JSON, come out in the same shape rather than as an HTML error
 * page, so a client only ever has to understand one error format.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        body.setType(URI.create(ex.getType()));
        body.setTitle(ex.getTitle());
        body.setInstance(URI.create(request.getRequestURI()));
        ex.getExtras().forEach(body::setProperty);
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * Spring answers a failed @Valid with 400. The contract says 422, so this
     * override exists purely to change that and attach our own type URI.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                detail.isBlank() ? "Request validation failed." : detail);
        body.setType(URI.create(Problems.VALIDATION_FAILED));
        body.setTitle("Validation failed");
        if (request instanceof ServletWebRequest servletWebRequest) {
            body.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
        }

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(body);
    }
}
