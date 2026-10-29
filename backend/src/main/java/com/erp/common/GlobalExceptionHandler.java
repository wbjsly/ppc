package com.erp.common;

import com.erp.security.AuthzDenyCounter;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /** 越权计数路 A（add-authz-detection-console design D3）：业务 403（HTTP 200+body）的唯一汇聚处 */
    private final AuthzDenyCounter authzDenyCounter;

    @ExceptionHandler(ServiceException.class)
    public R<Void> handleServiceException(ServiceException e) {
        log.error("ServiceException: {}", e.getMessage());
        if (e.getCode() == 403) {
            authzDenyCounter.hit(SecurityUtils.getCurrentUserId());
        }
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public R<Void> handleValidationException(org.springframework.web.bind.MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.error("ValidationException: {}", message);
        return R.fail(400, message);
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return R.fail("系统内部错误: " + e.getMessage());
    }
}
