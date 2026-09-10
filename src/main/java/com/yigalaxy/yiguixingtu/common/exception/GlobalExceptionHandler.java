package com.yigalaxy.yiguixingtu.common.exception;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：把各种异常统一转成规范的 Result 返回
 *
 * 【这个类存在的意义】
 *   没有它的话，每个 Controller 都要自己 try/catch，而且很容易漏；
 *   更糟的是漏掉的那种会直接抛到 Tomcat，返回一个格式完全不同的错误页
 *   （HTML 或者默认的 JSON），前端拿到之后"统一返回"的约定就断了。
 *   这里集中接住所有异常，保证**任何情况下响应体都是 {code, message, data}**。
 *
 * 【哪些错误返回真实 HTTP 状态码，哪些仍然 200 + body.code】
 *   判断标准只有一条：**这个错误是谁的问题**。
 *     · 请求本身有问题（地址不存在 404 / 方法用错 405 / 被限流 429）→ 真状态码。
 *       它们的受众不只是前端，还有 Nginx、监控、调用方的重试策略，
 *       这些基础设施只看状态码。
 *     · 业务语义与参数错误（密码错、参数不合法、请求体格式不对）→ 200 + body.code。
 *       前端本来就要针对每种 code 分支处理，改状态码会牵动所有接口的错误分支。
 *   这条分界线是量过"收益 vs 破坏面"之后划的，不是漏改，详见 README
 *   「统一返回与错误处理」。
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
     * 权限不足：@PreAuthorize 拦截（如游客调用管理员接口）
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
     * 请求的地址不存在（写错接口路径、或者接口已经下线）
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
     *   免得把前端所有接口的错误分支全部重新回归一遍。
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
     * HTTP 方法用错了（不是 GET/POST 的问题，是"这个地址不接受这个方法"）
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

    /**
     * 被应用层限流拦下（Resilience4j 的 @RateLimiter 拒绝了这次请求）
     *
     * 【这个异常是谁抛的】
     *   不是我们抛的，是 Resilience4j 自带的切面抛的：
     *   {@code @RateLimiter} 在配额用完时抛 {@link RequestNotPermitted}。
     *   也就是说"要不要拒"完全由库负责，我们只负责把它翻译成对前端友好的响应。
     *   —— 这正是"用主流现成方案"的意思：限流算法（令牌桶）、时间窗口、
     *      并发下的原子计数都在库里，我们一行都不用自己写。
     *
     * 【为什么返回真实的 HTTP 429，而不是继续用 200 + body.code】
     *   和 404 / 405 是同一个判断标准 —— 看这个错误"是谁的问题"：
     *     · "请求太频繁"属于【请求本身有问题】，不是业务语义的一部分
     *     · 而且这个状态码最大的受众不是我们的前端，而是【上游基础设施】：
     *       Nginx、CDN、监控告警、以及调用方自己的重试策略，它们都只看状态码。
     *       返回 429 它们才能识别出"该退避了 / 该告警了"；
     *       报 200 的话，一次限流事件在全站监控里看起来就是"成功"。
     *   业务错误（密码错、参数错）仍然保持 200 + body.code，理由见 README
     *   「统一返回与错误处理」—— 两者是分区处理，不是漏改。
     */
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<Result<?>> handleRateLimited(RequestNotPermitted e) {
        log.warn("请求被限流: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)                  // HTTP 429
                .body(Result.error(ResultCode.TOO_MANY_REQUESTS));     // {"code":429,...}
    }

    // =================================================================
    //  下面三个 handler 解决的是同一类问题：**请求本身写错了，却报成 500**
    //
    //  【它们是怎么被发现的】
    //    给新接口做真实环境验证时，我用 curl 手拼了一个 JSON body（引号写错了），
    //    后端回的是 {"code":500,"message":"服务器内部错误"}。
    //    顺手试了另外两种"客户端写错"的情形，也都报 500：
    //      · body 不是合法 JSON
    //      · 少传了必填的请求参数（@RequestParam 没给）
    //      · 路径/查询参数类型不对（id 传了 "abc"，而它是 Long）
    //
    //  【为什么必须区分，而不是"反正前端会提示失败"】
    //    ① 500 的语义是"服务器自己坏了" —— 监控告警会把它当成真故障，
    //       真正的服务器故障反而被这类噪音淹掉；而这三类错误都是
    //       调用方把请求写错了，重试一万次也不会好。
    //    ② 排查方向会被带偏：前端同学看到 500 会来问后端"是不是挂了"，
    //       后端翻日志才发现是参数问题 —— 一来一回就是半小时。
    //    ③ 与 404 / 405 / 429 保持同一套判断标准：**看这个错误是谁的问题**。
    //
    //  【为什么 HTTP 仍然返回 200，只给 body.code = 400】
    //    这三类都会被【前端自己】触发（表单或请求拼错了），属于"业务错误"这一区；
    //    改 HTTP 状态码要前后端一起回归 —— 那是 5.6 那个破坏性改动该做的事。
    // =================================================================

    /**
     * 请求体不是合法 JSON（或结构与目标对象对不上）。
     *
     * 【为什么日志里只留第一行】异常的 message 可能很长（包含解析位置与请求体片段），
     * 而我们要的只是"哪里坏了"这一句；完整堆栈在这个 handler 里没有排查价值。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", firstLine(e.getMessage()));
        return Result.error(ResultCode.PARAM_ERROR, "请求体格式不正确，请检查 JSON 是否合法");
    }

    /**
     * 少传了必填的请求参数。
     * 例如 PUT /admin/comment/{id}/status 没带 ?status=1。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填请求参数: {}", e.getParameterName());
        return Result.error(ResultCode.PARAM_ERROR, "缺少必填参数：" + e.getParameterName());
    }

    /**
     * 参数类型不对（路径变量或查询参数转不成目标类型）。
     * 例如 DELETE /admin/tag/abc —— id 声明的是 Long，"abc" 转不过去。
     *
     * 【为什么把参数名放进提示里】调用方看到"参数 id 格式不正确"能立刻定位；
     * 只说"参数校验失败"等于没说。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不正确: name={}, value={}", e.getName(), e.getValue());
        return Result.error(ResultCode.PARAM_ERROR, "参数 " + e.getName() + " 格式不正确");
    }

    /** 取异常信息的第一行（多行式的 message 只留开头那句） */
    private String firstLine(String message) {
        if (message == null) {
            return null;
        }
        int idx = message.indexOf('\n');
        return idx < 0 ? message : message.substring(0, idx);
    }
}
