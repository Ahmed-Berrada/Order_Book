package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.RejectReason;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every engine rejection and service refusal to an RFC 9457 problem detail with a
 * machine-readable {@code reason} (ADR-0005 §4). Malformed requests keep Spring's standard 400s.
 *
 * <p>Exceptions raised inside a future arrive wrapped in a {@code CompletionException}; Spring matches
 * these handlers against the cause.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler
    ResponseEntity<ProblemDetail> refused(RequestRefusedException e) {
        return switch (e.reason()) {
            case UNKNOWN_INSTRUMENT -> problem(HttpStatus.NOT_FOUND, "Unknown instrument",
                    "No listed instrument " + e.symbol(), e.reason().name());
            case OFF_TICK_PRICE -> problem(HttpStatus.UNPROCESSABLE_CONTENT, "Price off the tick grid",
                    "The price is not a multiple of the tick size of " + e.symbol(), e.reason().name());
            case OVERLOADED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(problemDetail(HttpStatus.SERVICE_UNAVAILABLE, "Instrument overloaded",
                            e.symbol() + " is busy; retry shortly", e.reason().name()));
            case HALTED -> problem(HttpStatus.SERVICE_UNAVAILABLE, "Instrument halted",
                    e.symbol() + " stopped after a journal failure", e.reason().name());
            case STOPPED -> problem(HttpStatus.SERVICE_UNAVAILABLE, "Venue stopping",
                    "The venue is shutting down", e.reason().name());
        };
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> rejected(OrderRejectedException e) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Order rejected",
                e.getMessage(), e.reason().name());
        response.getBody().setProperty("orderId", e.orderId());
        return response;
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> notResting(OrderNotRestingException e) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.NOT_FOUND, "Order not resting",
                e.getMessage(), RejectReason.UNKNOWN_ORDER.name());
        response.getBody().setProperty("orderId", e.orderId());
        return response;
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> outcomeUnknown(UncheckedIOException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Outcome unknown",
                "The command could not be journaled and may or may not have been recorded; "
                        + "check the book before resending", "OUTCOME_UNKNOWN");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail, String reason) {
        return ResponseEntity.status(status).body(problemDetail(status, title, detail, reason));
    }

    private static ProblemDetail problemDetail(HttpStatus status, String title, String detail, String reason) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("reason", reason);
        return problem;
    }
}
