package com.yigalaxy.yiguixingtu.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * =====================================================================
 * 把当前请求的 traceId 写进响应头 X-Trace-Id
 *
 * ============ 为什么需要这个过滤器 ============
 *
 * traceId 已经通过 Micrometer Tracing 自动打进了【日志】里（那是排查问题的关键），
 * 但日志只有服务端看得到。用户在前台点了"报错"却没法告诉你 traceId 是多少，
 * 于是"用户报了个错、我却在日志里对不上是哪次请求"这件事依然没闭环。
 * 这个过滤器的职责很单一：在响应头里带上 X-Trace-Id，
 * 前端就能把它显示给用户（见前端 w5.3），用户复制给你，你在日志里一搜即中。
 *
 * ============ 为什么不靠 Brave 自带的响应头，而要自己写 ============
 *
 * 本来 traceId 的响应头应该由 Brave 的 PropagatingSenderTracingObservationHandler
 * 在请求结束时自动写（配合 management.tracing.propagation.produce=b3 就能出 X-B3-TraceId）。
 * 但实测发现【出不来】。查下去找到根因：
 *
 *   Boot 4 把 tracing 的三个 handler（receiver / sender / default）包进了
 *   ObservationHandler.FirstMatchingCompositeObservationHandler ——
 *   也就是"每个阶段只执行【第一个】匹配的 handler"。
 *   而对一个入站 HTTP 请求，它的上下文同时是 ReceiverContext 又是 SenderContext，
 *   排在【第一位】的 receiver 永远先匹配上，于是 sender 的 onStop
 *   （写响应头那一步）永远轮不到执行。
 *
 *   这是 Boot 4 的默认行为，没有配置项能改（management.observations.* 里没有
 *   handler-grouping 这类开关）。与其为了一个响应头去和框架的
 *   handler 组合机制较劲，不如在最标准的扩展点 —— servlet 过滤器 ——
 *   里把当前 span 的 traceId 读出来写进响应头。这是 Brave 自己的 TracingFilter
 *   几十年来就在用的做法，不属于"自研一套东西"。
 *
 * ============ 为什么能在这里拿到 span ============
 *
 * ServerHttpObservationFilter（Boot 注册的，优先级最高）会包住整个过滤器链，
 * 并打开 observation 的作用域。所以本过滤器执行时，
 * traceId 已经通过 CurrentTraceContext 放进了当前线程，
 * {@code tracer.currentSpan()} 拿得到。若将来过滤器顺序被调整、
 * 导致这里拿不到，说明 observation 作用域没有覆盖到 —— 那时该查的是顺序，不是这个过滤器。
 *
 * ============ 用到的技术栈 ============
 *   · {@link Tracer#currentSpan()} —— 从线程局部取当前活动的 span。
 *     只读、不创建：没有 span 时返回 null，绝不会因为多读一次而多建一个 span
 *   · {@code OncePerRequestFilter} —— 保证一个请求最多执行一次
 *     （哪怕请求被 forward/include 多次），避免响应头被重复设置
 * =====================================================================
 */
@Component
public class TraceResponseHeaderFilter extends OncePerRequestFilter {

    /** 响应头名字。X- 前缀 + 语义明确，方便前端与排查工具一眼看懂 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final Tracer tracer;

    public TraceResponseHeaderFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null && span.context() != null) {
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        }
        filterChain.doFilter(request, response);
    }
}
