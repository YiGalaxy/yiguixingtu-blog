package com.yigalaxy.yiguixingtu.common.exception;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
        return Result.error(e.getResultCode(),e.getMessage());
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

    /**
     * 【新增】权限不足：@PreAuthorize 拦截（如游客调用管理员接口）
     *
     * 【为什么必须单独处理？】
     * 方法级权限校验抛出的 AccessDeniedException 发生在 Controller 方法内部，
     * 如果不单独接住，就会被下面 @ExceptionHandler(Exception.class) 兜底捕获，
     * 错报成"500 服务器内部错误"——把"没权限"和"服务器崩了"混为一谈。
     *
     * 【为什么返回 ResponseEntity 而不是 Result？】
     * 我们需要同时决定两件事：HTTP 状态码 = 403，以及响应体 = {"code":403,...}。
     * 只返回 Result 的话，HTTP 状态码会一直是 200，前端和测试都判断不出来。
     *
     * 【和 SecurityConfig 保持一致】
     * SecurityConfig 的 accessDeniedHandler 也是返回 403 + {"code":403,"message":"无权限访问"}，
     * 这样无论拒绝发生在"过滤器层"还是"方法层"，对前端都是同一种表现。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<?>> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)                  // HTTP 403
                .body(Result.error(ResultCode.FORBIDDEN));     // {"code":403,"message":"无权限访问"}
    }
}