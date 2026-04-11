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
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.List;

/**
 * 全局异常处理器。
 * 负责将不同类型的异常转换为统一的错误响应结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常。
     * 直接读取业务异常中携带的错误码和 HTTP 状态码返回给客户端。
     *
     * @param ex 业务异常对象
     * @return 统一错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        ErrorResponse body = ErrorResponse.of(ex.errorCode().code(), ex.getMessage(), List.of());
        return ResponseEntity.status(ex.httpStatus()).body(body);
    }

    /**
     * 处理请求体参数校验异常。
     * 将 Bean Validation 产生的字段错误收集为列表返回。
     *
     * @param ex 方法参数校验异常
     * @return HTTP 400 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        List<ApiFieldError> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toApiFieldError)
                .toList();
        ErrorResponse body = ErrorResponse.of(ErrorCode.VALIDATION_ERROR.code(), ErrorCode.VALIDATION_ERROR.defaultMessage(), errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 处理约束校验异常。
     * 主要覆盖路径参数、请求参数等触发的校验失败场景。
     *
     * @param ex 约束校验异常
     * @return HTTP 400 错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException ex) {
        List<ApiFieldError> errors = ex.getConstraintViolations()
                .stream()
                .map(v -> new ApiFieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        ErrorResponse body = ErrorResponse.of(ErrorCode.VALIDATION_ERROR.code(), ErrorCode.VALIDATION_ERROR.defaultMessage(), errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 处理请求体反序列化异常。
     * 主要覆盖非法枚举值、字段类型错误和 JSON 结构不合法等场景。
     *
     * @param ex 请求体解析异常
     * @return HTTP 400 错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        ErrorResponse body = ErrorResponse.of(ErrorCode.VALIDATION_ERROR.code(), "请求体格式不正确", List.of());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 处理未预期的系统异常。
     * 对外统一返回 500，避免直接暴露内部堆栈细节。
     *
     * @param ex 未捕获异常
     * @return HTTP 500 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        ErrorResponse body = ErrorResponse.of(ErrorCode.INTERNAL_ERROR.code(), ErrorCode.INTERNAL_ERROR.defaultMessage(), List.of());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * 将 Spring 的字段错误对象转换为接口层统一结构。
     *
     * @param fieldError Spring 字段错误对象
     * @return 统一字段错误结构
     */
    private ApiFieldError toApiFieldError(FieldError fieldError) {
        return new ApiFieldError(fieldError.getField(), fieldError.getDefaultMessage());
    }
}
