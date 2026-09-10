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
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * 【新增】请求的地址不存在（写错接口路径、或者接口已经下线）
     *
     * 【为什么必须单独接住 —— 这是被一次真实的困惑逼出来的】
     *   这个项目所有异常都走「统一返回」：HTTP 200 + body 里的 code。
     *   这条约定对【业务错误】是对的（前端只看 code，不用关心状态码），
     *   但它有个副作用：连"地址不存在"这种【请求本身有问题】的情况
     *   也被转成了 HTTP 200 + code 500。
     *
     *   实测三个例子，看完就知道为什么必须区分：
     *     · GET /article/999999      → HTTP 200 + {"code":404}   业务上可接受
     *     · GET /no-such-endpoint    → HTTP 401（因为 Security 拦在前面）→ 让人以为"没登录"
     *     · POST /article/page       → HTTP 401                  → 同上
     *   也就是说：地址拼错、方法用错，看到的都是"你没登录" / "服务器内部错误"，
     *   排查时会一路往错误的方向查（查登录、查服务端），而真实原因是最简单的那一个。
     *
     *   而且这不只影响人：Nginx 访问日志、监控告警、负载均衡都【只看状态码】，
     *   全站错误都记成 200，错误率永远是 0%。
     *
     * 【为什么这两个（404 / 405）可以返回真状态码，而业务错误仍然保持 200】
     *   因为前端【永远不会主动去请求一个不存在的地址】——
     *   正常流程里根本走不到这两个 handler，所以改它们对前端零影响。
     *   业务错误（密码错、参数错）继续用 200 + code，
     *   免得把前端 19 个接口的错误分支全部重新回归一遍。
     *   —— 这是"改动收益"和"破坏面"之间量过之后的取舍，不是漏改了。
     *
     * 【匿名用户请求不存在的地址仍然是 401，这是有意的】
     *   SecurityConfig 里 anyRequest().authenticated() 在【路由之前】就拦住了，
     *   所以未登录者拿不到"这个地址存不存在"的信息。
     *   这是刻意的：不向未登录者暴露站点有哪些接口。
     *   这两个 handler 服务的是【已登录/已放行】之后才暴露出来的地址错误。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<?>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("请求的地址不存在: {} {}", e.getHttpMethod(), e.getResourcePath());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Result.error(ResultCode.ENDPOINT_NOT_FOUND));
    }

    /**
     * 【新增】HTTP 方法用错了（不是 GET/POST 的问题，是"这个地址不接受这个方法"）
     *
     * 比如用 POST 去打一个只声明了 @GetMapping 的接口。
     * 不接住的话会落到兜底的 Exception 分支，报成 500「服务器内部错误」——
     * 调用方明明是自己写错了方法，却看起来像后端挂了，很容易互相甩锅。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}（该地址支持 {}）", e.getMethod(), e.getSupportedHttpMethods());
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.error(ResultCode.METHOD_NOT_ALLOWED));
    }
}