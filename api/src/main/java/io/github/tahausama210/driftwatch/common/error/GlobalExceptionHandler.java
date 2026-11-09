package io.github.tahausama210.driftwatch.common.error;

import io.github.tahausama210.driftwatch.common.WireEnum;
import io.github.tahausama210.driftwatch.common.web.RequestBodyTooLargeException;
import java.net.URI;
import java.time.temporal.Temporal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Renders every error as RFC 9457 {@code application/problem+json}.
 *
 * <p>Spring MVC's own exceptions are handled by {@link ResponseEntityExceptionHandler}; the overrides
 * here add an {@code errors[]} array with field paths for validation failures. Problems use the
 * default {@code about:blank} type: the HTTP status fully describes each one, and clients branch on
 * {@code errors[].field} rather than on problem types. Security failures and the request body limit
 * filter are routed here through the {@code HandlerExceptionResolver} so that all error responses
 * share one implementation. Details are always written by this service and never include exception
 * messages from libraries, stack traces or SQL.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String ERRORS_PROPERTY = "errors";
    private static final URI ABOUT_BLANK = URI.create("about:blank");
    private static final String VALIDATION_DETAIL = "The request is invalid; see errors for details.";
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getAllErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();
        return validationProblem(ex, violations, headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                parameterName(result.getMethodParameter()), messageOf(error.getDefaultMessage()))))
                .toList();
        return validationProblem(ex, violations, headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MethodArgumentTypeMismatchException mismatch
                && mismatch.getParameter().hasParameterAnnotation(PathVariable.class)) {
            // A malformed identifier in the path cannot name an existing resource.
            return problem(ex, HttpStatus.NOT_FOUND, "No resource exists at this path.", headers, request);
        }
        String field = ex.getPropertyName() == null ? "request" : ex.getPropertyName();
        return validationProblem(
                ex, List.of(new FieldViolation(field, describeExpected(ex.getRequiredType()))), headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        RequestBodyTooLargeException tooLarge = findCause(ex, RequestBodyTooLargeException.class);
        if (tooLarge != null) {
            return handleRequestBodyTooLarge(tooLarge, request);
        }
        MismatchedInputException mismatch = findCause(ex, MismatchedInputException.class);
        if (mismatch != null && !mismatch.getPath().isEmpty()) {
            FieldViolation violation =
                    new FieldViolation(jsonPath(mismatch.getPath()), describeExpected(mismatch.getTargetType()));
            return validationProblem(ex, List.of(violation), headers, request);
        }
        return problem(
                ex, HttpStatus.BAD_REQUEST, "The request body is missing or is not valid JSON.", headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(ex, HttpStatus.NOT_FOUND, "No endpoint exists at this path.", headers, request);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> handleNotFound(NotFoundException ex, WebRequest request) {
        return problem(ex, HttpStatus.NOT_FOUND, ex.getMessage(), new HttpHeaders(), request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ex.getMessage(), new HttpHeaders(), request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> handleInvalidRequest(InvalidRequestException ex, WebRequest request) {
        return validationProblem(ex, ex.violations(), new HttpHeaders(), request);
    }

    @ExceptionHandler(RequestBodyTooLargeException.class)
    ResponseEntity<Object> handleRequestBodyTooLarge(RequestBodyTooLargeException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONTENT_TOO_LARGE, ex.getMessage(), new HttpHeaders(), request);
    }

    /** The credential store could not be queried; this is a server fault, not a bad credential. */
    @ExceptionHandler(AuthenticationServiceException.class)
    ResponseEntity<Object> handleAuthenticationServiceFailure(AuthenticationServiceException ex, WebRequest request) {
        log.error("Authentication could not be performed", ex);
        return internalError(ex, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthenticationFailure(AuthenticationException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return problem(ex, HttpStatus.UNAUTHORIZED, "A valid bearer token is required.", headers, request);
    }

    /** Spring Security's firewall refused the URL, e.g. for {@code //}, {@code ;} or an encoded {@code /}. */
    @ExceptionHandler(RequestRejectedException.class)
    ResponseEntity<Object> handleRequestRejected(RequestRejectedException ex, WebRequest request) {
        return problem(ex, HttpStatus.BAD_REQUEST, "The request URL is not allowed.", new HttpHeaders(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return problem(
                ex,
                HttpStatus.FORBIDDEN,
                "These credentials may not access this resource.",
                new HttpHeaders(),
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return internalError(ex, request);
    }

    /**
     * Every problem response goes through here, so the media type and the explicit {@code type}
     * member (which Spring leaves unset, and the contract requires) are set in exactly one place.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getType() == null) {
            problem.setType(ABOUT_BLANK);
        }
        HttpHeaders problemHeaders = HttpHeaders.copyOf(headers);
        problemHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(body, problemHeaders, statusCode);
    }

    private ResponseEntity<Object> internalError(Exception ex, WebRequest request) {
        return problem(
                ex, HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", new HttpHeaders(), request);
    }

    private ResponseEntity<Object> validationProblem(
            Exception ex, List<FieldViolation> violations, HttpHeaders headers, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, VALIDATION_DETAIL);
        body.setProperty(
                ERRORS_PROPERTY,
                violations.stream()
                        .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                        .toList());
        return render(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ResponseEntity<Object> problem(
            Exception ex, HttpStatus status, String detail, HttpHeaders headers, WebRequest request) {
        return render(ex, ProblemDetail.forStatusAndDetail(status, detail), headers, status, request);
    }

    private ResponseEntity<Object> render(
            Exception ex, ProblemDetail body, HttpHeaders headers, HttpStatus status, WebRequest request) {
        ResponseEntity<Object> response = handleExceptionInternal(ex, body, headers, status, request);
        // handleExceptionInternal only returns null when the response is already committed, which
        // cannot happen for the errors routed here; fall back to building the entity directly.
        return response != null ? response : createResponseEntity(body, headers, status, request);
    }

    private static FieldViolation toViolation(ObjectError error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
        return new FieldViolation(field, messageOf(error.getDefaultMessage()));
    }

    private static String messageOf(@Nullable String message) {
        return message == null ? "is invalid" : message;
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        String name = parameter.getParameterName();
        return name == null ? "parameter" : name;
    }

    /** Builds {@code results[3].severity} from Jackson's path of property names and array indexes. */
    private static String jsonPath(List<JacksonException.Reference> path) {
        StringBuilder field = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            if (reference.getPropertyName() != null) {
                if (!field.isEmpty()) {
                    field.append('.');
                }
                field.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                field.append('[').append(reference.getIndex()).append(']');
            }
        }
        return field.toString();
    }

    private static String describeExpected(@Nullable Class<?> type) {
        if (type == null) {
            return "has an invalid value";
        }
        if (type.isEnum()) {
            return "must be one of " + WireEnum.allowedValues(type);
        }
        if (type == UUID.class) {
            return "must be a UUID";
        }
        if (Temporal.class.isAssignableFrom(type)) {
            return "must be an RFC 3339 date-time";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "must be an integer";
        }
        if (type == String.class) {
            return "must be a string";
        }
        return "has an invalid value";
    }

    private static <T extends Throwable> @Nullable T findCause(Throwable ex, Class<T> type) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }
}
