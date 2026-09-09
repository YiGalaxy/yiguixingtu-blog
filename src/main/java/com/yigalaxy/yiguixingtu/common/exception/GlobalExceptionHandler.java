package com.yigalaxy.yiguixingtu.common.exception;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把各种异常统一转成规范的 Result 返回
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** ① 我们自己抛的业务异常 */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getResultCode());
    }

    /** ② 参数校验失败（@Valid 触发） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return Result.error(ResultCode.PARAM_ERROR, msg);
    }

    /** ③ 账号被禁用 */
    @ExceptionHandler(DisabledException.class)
    public Result<?> handleDisabled(DisabledException e) {
        log.warn("账号被禁用: {}", e.getMessage());
        return Result.error(ResultCode.ACCOUNT_DISABLED);
    }

    /** ④ 认证失败（用户名/密码错误、用户不存在等） */
    @ExceptionHandler(AuthenticationException.class)
    public Result<?> handleAuth(AuthenticationException e) {
        log.warn("认证失败: {}", e.getMessage());
        return Result.error(ResultCode.BAD_CREDENTIALS);
    }

    /** ⑤ 其他未预料异常 */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(ResultCode.ERROR);
    }
}