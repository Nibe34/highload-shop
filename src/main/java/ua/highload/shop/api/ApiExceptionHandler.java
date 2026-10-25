package ua.highload.shop.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ua.highload.shop.domain.ConflictException;
import ua.highload.shop.domain.NotFoundException;
import ua.highload.shop.domain.UnauthorizedException;

/** Перетворює доменні винятки на RFC 7807 Problem Details з коректними HTTP-статусами. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    ProblemDetail unauthorized(UnauthorizedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
        return pd;
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail badParam(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Redis (сесії/кошики) недоступний: контрольована 503 замість 500 — каталог при цьому продовжує працювати. */
    @ExceptionHandler({org.springframework.data.redis.RedisConnectionFailureException.class,
            org.springframework.data.redis.RedisSystemException.class})
    ProblemDetail redisDown(RuntimeException e) {
        log.warn("Session store unavailable: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Session store temporarily unavailable");
    }

    /** Primary недоступний: запис неможливий (Read-Only Mode), читання з Replica продовжують працювати. */
    @ExceptionHandler({org.springframework.jdbc.CannotGetJdbcConnectionException.class,
            org.springframework.transaction.CannotCreateTransactionException.class})
    ResponseEntity<ProblemDetail> databaseDown(RuntimeException e) {
        if (isPoolTimeout(e)) {
            // Вузол живий, але пул з'єднань вичерпано: швидка відмова (load shedding) замість черги без кінця.
            var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "Database is overloaded, retry later");
            pd.setProperty("reason", "connection-pool-exhausted");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1").body(pd);
        }
        log.warn("Database unavailable: {}", e.getMessage());
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Primary database unavailable: service is in read-only mode");
        pd.setProperty("mode", "read-only");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(pd);
    }

    /**
     * HikariCP: "Connection is not available, request timed out" без вкладеної причини — пул вичерпано, БД доступна.
     * Якщо БД недоступна, Hikari додає причину (останню помилку створення з'єднання, напр. connection refused).
     */
    private static boolean isPoolTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException) {
                return t.getCause() == null;
            }
        }
        return false;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        log.debug("Integrity violation", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Data integrity violation");
    }
}
