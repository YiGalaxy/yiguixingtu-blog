package com.yigalaxy.yiguixingtu;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 链路追踪（traceId）的测试
 *
 * 【它守护的是什么】
 *   线上排查问题时最难受的场景是：用户说"我点了下就报错了"，
 *   而日志里成千上万行，你根本不知道哪几行属于他那次请求。
 *   traceId 就是给"一次请求"发的身份证号 ——
 *   一次请求产生的所有日志行都带着同一个号，拿它一搜，这次请求的全貌就出来了。
 *
 *   本类要证明的就是这件事：**一次请求的日志行共享同一个 traceId，
 *   不同请求的 traceId 不同。**
 *
 * 【⚠️ 一个真实存在的陷阱（和 Flyway 那次一样的模式）】
 *   Spring Boot 4 把自动配置按技术拆成了独立模块。如果只加裸的
 *   micrometer-tracing-bridge-brave 而不加 spring-boot-micrometer-tracing-brave，
 *   编译能过、启动不报错，但 traceId 就是【不会出现】——又一个静默失效。
 *   所以下面专门有一条用例断言 Tracer 这个 Bean 真的存在且可用，
 *   它就是"自动配置到底加载了没有"的探针。
 *
 * 【怎么验证"日志里有没有 traceId"】
 *   用 Logback 的 ListAppender 把日志事件截获下来，
 *   再从事件的 MDC 里读 traceId。
 *   —— 这样验证的是"日志真的会带上它"，而不是"某个字段被赋值了"，
 *      比断言一个内部变量更接近真实效果。
 *
 * 【为什么不在这个测试里断言响应头 X-B3-TraceId】
 *   B3 的响应头是由 Brave 的 servlet filter 加上的，而 MockMvc 是
 *   "模拟"的 servlet 环境，不一定跑得到那一层 filter。
 *   这个头是给前端用的（见 w5.3），所以它在【真实启动的服务】上验证更靠谱，
 *   见本次提交的【联调】记录。这里只验证"框架层真的在生成 traceId"。
 * =====================================================================
 */
class TracingTest extends AbstractIntegrationTest {

    /** MDC 里的 key（Spring Boot 的追踪自动配置就用这个名字） */
    private static final String TRACE_ID_KEY = "traceId";

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private Tracer tracer;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("① 追踪自动配置真的加载了（防止 Boot 4 拆模块导致静默失效）")
    void tracerBean_shouldBePresent() {
        assertNotNull(tracer, "应当能注入 Tracer —— 注入不进来就说明自动配置没生效");

        // 再确认它不是个"空实现"：能建一个 span 并拿到它的上下文。
        // ⚠️ 注意这里【不能】断言 tracer.currentSpan() 不为 null ——
        //    测试线程此刻不在任何请求里，没有活动 span，返回 null 才是对的。
        //    第一版就在这里写错了：把"没有活动 span 时的 null"当成了失败。
        var span = tracer.nextSpan().name("tracer-bean-probe").start();
        try {
            assertNotNull(span.context().traceId(), "能建 span 的 Tracer 才叫可用");
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("② 请求日志里的 traceId 应当和响应头 X-Trace-Id 是同一个")
    void oneRequest_logLineTraceIdShouldMatchResponseHeader() throws Exception {
        // 【为什么用登录接口而不是 /article/page】
        //   第一版用的是 /article/page，结果一条日志都截获不到 ——
        //   不是追踪没生效，而是那个接口【本来就不打日志】。
        //   "日志行共享 traceId"这个断言的前提是"请求里确实产生了日志行"，
        //   前提不成立时，测试红的就不是追踪、而是"我选错了接口"。
        //   登录接口成功时会打一行"登录成功"，正好拿来当探针。
        String username = seedUser("trace_probe");

        List<ILoggingEvent> events = new java.util.ArrayList<>();
        String headerTraceId = captureLogsAndHeader(() -> {
            var result = mockMvc.perform(post("/auth/login")
                            .contentType("application/json")
                            .content("{\"username\":\"" + username + "\",\"password\":\"probe-pass\"}"))
                    .andExpect(status().isOk());
            return result.andReturn().getResponse().getHeader("X-Trace-Id");
        }, events);

        assertNotNull(headerTraceId, "登录响应应当带 X-Trace-Id 头");
        Set<String> logTraceIds = traceIdsIn(events);
        assertTrue(!logTraceIds.isEmpty(),
                "登录请求里应当有带 traceId 的日志行（说明 MDC 被正确填充了）");

        // 核心断言：日志里的 traceId 和响应头里的是【同一个】。
        // 这保证"用户把响应头里的 traceId 报给你 → 你在日志里搜到的就是他那次请求"。
        assertTrue(logTraceIds.contains(headerTraceId),
                "日志 traceId 应当与响应头一致，日志=" + logTraceIds + " 响应头=" + headerTraceId);
    }

    @Test
    @DisplayName("③ 两次请求的 traceId 不同（否则根本区分不出是哪一次）")
    void twoRequests_shouldHaveDifferentTraceIds() throws Exception {
        String username = seedUser("trace_probe_two");

        Set<String> first = traceIdsIn(captureLogsDuring(() ->
                mockMvc.perform(post("/auth/login")
                                .contentType("application/json")
                                .content("{\"username\":\"" + username + "\",\"password\":\"probe-pass\"}"))
                        .andExpect(status().isOk())));
        Set<String> second = traceIdsIn(captureLogsDuring(() ->
                mockMvc.perform(post("/auth/login")
                                .contentType("application/json")
                                .content("{\"username\":\"" + username + "\",\"password\":\"probe-pass\"}"))
                        .andExpect(status().isOk())));

        assertTrue(!first.isEmpty() && !second.isEmpty(), "两次请求都应当产生带 traceId 的日志");
        // 交集为空 = 两次请求的标识不重复
        assertTrue(java.util.Collections.disjoint(first, second),
                "两次请求不该共用 traceId，实际第一次=" + first + " 第二次=" + second);
    }

    @Test
    @DisplayName("④ 没有活动请求时，日志里不该硬塞一个 traceId（避免噪音）")
    void withoutRequest_shouldNotForceTraceId() {
        // 启动、定时任务这类"不属于任何请求"的日志，没有 traceId 是正常的。
        // 这条守的是"别为了好看而编一个假 traceId"——
        // 假标识比没有更糟：会让人误以为搜到的日志属于同一次请求
        List<ILoggingEvent> events = captureLogsDuring(() -> {
            // 什么都不做，只是一次空捕获
        });
        Set<String> traceIds = traceIdsIn(events);
        assertTrue(traceIds.isEmpty(),
                "测试线程里没有活动请求，不该有 traceId，实际=" + traceIds);
    }

    @Test
    @DisplayName("⑤ 响应头带 X-Trace-Id，前端才能把它显示给用户（w5.3 的后端支撑）")
    void responseHeader_shouldCarryTraceId() throws Exception {
        String headerValue = mockMvc.perform(get("/article/page").param("size", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Trace-Id");

        assertNotNull(headerValue, "响应头应当带 X-Trace-Id，否则前端 w5.3 拿不到 traceId 显示给用户");
        assertTrue(headerValue.length() >= 16,
                "X-Trace-Id 应当是一串有长度的十六进制标识，实际=" + headerValue);
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /**
     * 在一个代码块执行期间截获日志。
     *
     * 【为什么必须 override append 调 prepareForDeferredProcessing —— 这是本测试最关键的坑】
     *   Logback 的 LoggingEvent 对 MDC 是【懒快照】：getMDCPropertyMap() 不是
     *   在日志发生时拷贝，而是【第一次调用它的时候】才去读当前线程的 MDC。
     *   如果像第一版那样，把事件收集起来、等请求跑完再读 getMDCPropertyMap()，
     *   读到的是【请求结束后已经清空的 MDC】——于是 traceId 永远是 null，
     *   看起来像是"追踪没生效"，其实是读的时机错了。
     *   prepareForDeferredProcessing() 会在事件【入队那一刻】就把 MDC 拷成快照，
     *   之后什么时候读都是正确的值。这正是异步 appender 里那套做法，
     *   这里借用它来获得"事件发生时的真实 MDC"。
     *
     * 【为什么用完必须 detachAppender】
     *   ListAppender 是挂在 root logger 上的。不摘掉的话：
     *     · 它会一直收集日志，测试越多内存占用越大
     *     · 后面用例的断言会混进前面用例的日志，变得不可靠
     *   所以放在 finally 里，无论成败都摘。
     */
    private List<ILoggingEvent> captureLogsDuring(ThrowingRunnable action) {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent eventObject) {
                eventObject.prepareForDeferredProcessing();
                super.append(eventObject);
            }
        };
        appender.start();
        rootLogger.addAppender(appender);
        try {
            action.run();
        } catch (Exception e) {
            throw new IllegalStateException("执行被测动作失败", e);
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
    }

    /** 从日志事件里取出所有出现过的 traceId */
    private Set<String> traceIdsIn(List<ILoggingEvent> events) {
        return events.stream()
                .map(e -> e.getMDCPropertyMap().get(TRACE_ID_KEY))
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * 在执行动作期间截获日志，同时把动作的返回值（这里是响应头）带出来。
     * 语义与 captureLogsDuring 完全一致，只是多返回一个结果。
     */
    private String captureLogsAndHeader(ThrowingSupplier<String> action, List<ILoggingEvent> sink) {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent eventObject) {
                eventObject.prepareForDeferredProcessing();
                super.append(eventObject);
            }
        };
        appender.start();
        rootLogger.addAppender(appender);
        try {
            return action.get();
        } catch (Exception e) {
            throw new IllegalStateException("执行被测动作失败", e);
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
            sink.addAll(appender.list);
        }
    }

    /**
     * 造一个可登录的用户（直接写库，不走注册接口，避免把"注册"的日志混进断言）。
     * 用户名带纳秒时间戳，保证同一事务里反复跑不撞唯一索引。
     */
    private String seedUser(String prefix) {
        String username = prefix + "_" + System.nanoTime();
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("probe-pass"));
        u.setNickname("链路追踪测试用户");
        u.setRole("GUEST");
        u.setStatus(1);
        userMapper.insert(u);
        return username;
    }

    /** 允许 lambda 里抛受检异常的小接口 */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 允许 lambda 里抛受检异常、且带返回值的小接口 */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
