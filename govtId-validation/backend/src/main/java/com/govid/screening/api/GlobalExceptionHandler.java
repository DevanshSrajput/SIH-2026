package com.govid.screening.api;

import com.govid.screening.api.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Turns every failure into the same JSON shape.
 *
 * <p>A stack trace on the wire is two problems at once: it tells an officer nothing they
 * can act on, and it tells anyone else the framework versions and package layout of a
 * border security system. Each handler below converts one class of failure into a sentence
 * that names what to do about it, and the detail stays in the server log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleNotConfigured(IllegalStateException e) {
        // Raised when a required downstream - the face service, typically - is not set up.
        // That is an operator problem, not a caller problem, so it is a 503.
        log.warn("Service unavailable: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE,
                "The uploaded image exceeds the size limit. Re-scan at a lower resolution.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException e) {
        return body(HttpStatus.BAD_REQUEST,
                "The request is missing its '" + e.getRequestPartName() + "' part.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException e) {
        return body(HttpStatus.BAD_REQUEST,
                "The required parameter '" + e.getParameterName() + "' was not supplied.");
    }

    /**
     * An unparseable enum arrives here - a document type the platform does not know, for
     * instance. The message lists what is accepted rather than restating what was rejected.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        Class<?> required = e.getRequiredType();
        String accepted = required != null && required.isEnum()
                ? " Accepted values: " + String.join(", ",
                        java.util.Arrays.stream(required.getEnumConstants())
                                .map(String::valueOf).toList()) + "."
                : "";
        return body(HttpStatus.BAD_REQUEST,
                "'" + e.getValue() + "' is not a valid value for '" + e.getName() + "'." + accepted);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST,
                message.isEmpty() ? "The request failed validation." : message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST,
                "The request body could not be read. It is not valid JSON, or a field has "
                        + "the wrong type.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "No such endpoint.");
    }

    /**
     * The face service being down must not read as a screening result. It is reported as
     * an upstream failure so the console can say "face verification unavailable" rather
     * than rendering a zero similarity as a mismatch.
     */
    @ExceptionHandler({ResourceAccessException.class, RestClientException.class})
    public ResponseEntity<ApiError> handleUpstream(RestClientException e) {
        log.error("Upstream service call failed", e);
        return body(HttpStatus.BAD_GATEWAY,
                "A service this request depends on could not be reached. The screening was "
                        + "not completed; no result should be inferred from this response.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        // The detail goes to the log, not to the caller.
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR,
                "The request could not be completed. The failure has been logged; quote the "
                        + "time of this response when reporting it.");
    }

    private static ResponseEntity<ApiError> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message == null ? "" : message));
    }
}
