package com.petrolpump.credit.common;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    /** Friendly messages for constraint names that users can actually hit. */
    private static final Map<String, String> CONSTRAINT_MESSAGES = Map.of(
            "ux_users_username", "Username already exists",
            "ux_vehicles_active_number", "This vehicle number is already registered to an active vehicle",
            "credit_limit_exceptions_check", "Valid-until must be after valid-from",
            "ux_payments_verified_reference", "This UTR / reference number has already been verified on another payment",
            "ck_payments_cash_needs_cashier", "Cash must be recorded by a cashier before it can be verified");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException ex) {
        return ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
    }

    /** 409 with duplicate=true so the client can offer "save anyway". */
    @ExceptionHandler(DuplicateWarningException.class)
    ProblemDetail duplicate(DuplicateWarningException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setProperty("duplicate", true);
        pd.setProperty("existingRef", ex.existingRef());
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission for this action");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail stale(OptimisticLockingFailureException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This record was changed by someone else. Reload and try again.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException ex) {
        String msg = rootMessage(ex);
        return CONSTRAINT_MESSAGES.entrySet().stream()
                .filter(e -> msg.contains(e.getKey()))
                .findFirst()
                .map(e -> ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getValue()))
                .orElseGet(() -> ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, firstLine(msg)));
    }

    /** Business rules raised by DB triggers (RAISE EXCEPTION, SQLSTATE P0001). */
    @ExceptionHandler(DataAccessException.class)
    ProblemDetail dataAccess(DataAccessException ex) {
        if (ex.getMostSpecificCause() instanceof SQLException sql && "P0001".equals(sql.getSQLState())) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, firstLine(sql.getMessage()));
        }
        throw ex;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? "" : t.getMessage();
    }

    private static String firstLine(String s) {
        String line = s.lines().findFirst().orElse(s);
        return line.startsWith("ERROR: ") ? line.substring(7) : line;
    }
}
