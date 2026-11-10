package com.databasesystem.hospital.common;

import jakarta.validation.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);
    private static final Map<String, String> UNIQUE_CODES = Map.ofEntries(
            Map.entry("uq_account_login", "LOGIN_ID_TAKEN"),
            Map.entry("uq_patient_identity", "DUPLICATE_PATIENT"),
            Map.entry("uq_reservation_occupied_slot", "SLOT_UNAVAILABLE"),
            Map.entry("uq_visit_reservation", "ALREADY_CHECKED_IN"),
            Map.entry("uq_record_visit", "MEDICAL_RECORD_EXISTS"),
            Map.entry("uq_payment_visit", "PAYMENT_ALREADY_EXISTS"),
            Map.entry("uq_fee_effective", "DUPLICATE_FEE_EFFECTIVE_FROM"),
            Map.entry("uq_doctor_schedule", "DUPLICATE_SCHEDULE"),
            Map.entry("uq_department_name", "DUPLICATE_DEPARTMENT_NAME"),
            Map.entry("uq_age_condition", "DUPLICATE_DUR_RULE"),
            Map.entry("drug_interaction_pkey", "DUPLICATE_DUR_RULE"));

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> business(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), exception.details()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class})
    ResponseEntity<ApiError> invalid(Exception exception) {
        return business(ApiException.badRequest());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException exception) {
        return business(new ApiException(405, "METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException exception) {
        return business(new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "요청의 Content-Type을 확인하세요."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(NoResourceFoundException exception) {
        return business(ApiException.notFound());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException sql && "23505".equals(sql.getSQLState())
                    && sql.getServerErrorMessage() != null) {
                String code = UNIQUE_CODES.get(sql.getServerErrorMessage().getConstraint());
                if (code != null) {
                    return business(ApiException.conflict(code, "동일한 데이터가 이미 저장되어 있습니다."));
                }
            }
        }
        return unexpected(exception);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("Unhandled API failure: {}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(new ApiError("INTERNAL_SERVER_ERROR", "서버에서 요청을 처리하지 못했습니다.", null));
    }
}
