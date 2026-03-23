package com.zhiguang.be.common.exception;

import com.zhiguang.be.common.api.ApiFieldError;
import com.zhiguang.be.common.api.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
/**
 * 类说明。
 */
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    /**
     * 方法说明。
     */
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        ErrorResponse body = ErrorResponse.of(ex.errorCode().code(), ex.getMessage(), List.of());
        return ResponseEntity.status(ex.httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    /**
     * 方法说明。
     */
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        List<ApiFieldError> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toApiFieldError)
                .toList();
        ErrorResponse body = ErrorResponse.of(ErrorCode.VALIDATION_ERROR.code(), ErrorCode.VALIDATION_ERROR.defaultMessage(), errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    /**
     * 方法说明。
     */
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException ex) {
        List<ApiFieldError> errors = ex.getConstraintViolations()
                .stream()
                .map(v -> new ApiFieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        ErrorResponse body = ErrorResponse.of(ErrorCode.VALIDATION_ERROR.code(), ErrorCode.VALIDATION_ERROR.defaultMessage(), errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    /**
     * 方法说明。
     */
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        ErrorResponse body = ErrorResponse.of(ErrorCode.INTERNAL_ERROR.code(), ErrorCode.INTERNAL_ERROR.defaultMessage(), List.of());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * 方法说明。
     */
    private ApiFieldError toApiFieldError(FieldError fieldError) {
        return new ApiFieldError(fieldError.getField(), fieldError.getDefaultMessage());
    }
}

