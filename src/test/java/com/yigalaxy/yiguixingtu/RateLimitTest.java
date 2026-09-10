package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.common.ResultCode;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 接口限流测试（Resilience4j @RateLimiter）
 *
 * 【它守护的是什么】
 *   限流的失败方式很特别：**配额一旦配错，功能看起来完全正常**。
 *   注解写错名字 → 库会抛"找不到这个实例"（还好，能发现）；
 *   但配额调太大、或者切面根本没生效（比如少了 AOP 依赖），
 *   表现就是"限流悄悄地不存在了"——接口一切正常，直到被刷爆。
 *   所以必须有断言真的把配额打满、看着它返回 429。
 *
 * 【怎么让测试可控 —— 关键的一步：运行时改配额】
 *   ⚠️ 登录接口的线上配额是 5 次/分钟，而整个测试套件里有十几个测试类要登录。
 *   如果直接按线上配额去触发 429，那这条用例会把配额耗光，
 *   后面的用例全部收到 429 —— 报错信息还是"登录失败"，极难排查。
 *   （基类里已经加了"每条用例前重置限流器"来缓解，但同一分钟内累计调用
 *     仍然可能踩线，靠重置只是缓解而不是根治。）
 *
 *   所以这里的做法是：**用例自己在运行时把配额改小**（改成 2 次），
 *   触发限流、断言 429，再用 @AfterEach 恢复原配置并重置。
 *   Resilience4j 提供了 changeLimitForPeriod / changeTimeoutDuration 这两个方法，
 *   本来就是给"运行时动态调整"用的，这里正好派上用场。
 *   好处是：线上配额（那条最重要的配置）保持真实值不动，
 *   而测试依然能确定性地复现"配额用完"这个状态。
 *
 * 【为什么断言 HTTP 429 而不是 HTTP 200 + code】
 *   限流属于"请求本身有问题"这一类（和 404 / 405 同一类），
 *   返回真实状态码，上游的 Nginx / 监控 / 重试策略才看得懂。
 *   详见 GlobalExceptionHandler#handleRateLimited 与 README「统一返回与错误处理」。
 * =====================================================================
 */
class RateLimitTest extends AbstractIntegrationTest {

    /** 和 AuthController 上 @RateLimiter(name = ...) 保持一致 */
    private static final String LOGIN_LIMITER = "loginRateLimiter";
    private static final String PAGE_LIMITER = "articlePageRateLimiter";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    /** 用例开始时记下原始配置，结束时恢复 —— 不能把线上的配额改小了留给下一个用例 */
    private RateLimiterConfig originalLoginConfig;
    private RateLimiterConfig originalPageConfig;

    /**
     * 把某个限流器换成"配额很小的全新实例"。
     *
     * 【为什么要换实例，而不是改配额】
     *   第一版用 {@code limiter.changeLimitForPeriod(2)} 想缩小配额，实测【不生效】：
     *   改完之后 {@code getRateLimiterConfig()} 确实显示 limit=2，
     *   但 {@code getMetrics().getAvailablePermissions()} 还是原来的 5 ——
     *   配置变了、计数没变，于是请求照样全部通过（用例报 expected 429 but was 200）。
     *   它内部是 updateAndGet 出一个新 State，而配额计数器沿用旧引用，
     *   所以"改配额"并不等于"重新开窗"。
     *
     *   换成 {@code registry.replace(name, 新实例)} 之后语义就明确了：
     *   新实例 = 新计数器 + 满窗口 + 我们指定的配额。
     *   切面每次调用都按名字从注册表取，所以换掉立刻生效。
     */
    private RateLimiter replaceWithLimit(String name, int limitForPeriod) {
        RateLimiter current = rateLimiterRegistry.rateLimiter(name);
        RateLimiterConfig config = RateLimiterConfig.from(current.getRateLimiterConfig())
                .limitForPeriod(limitForPeriod)
                // timeout-duration 设成 0：配额用完就立刻拒绝，不等待。
                // 不设的话请求会在这儿等配额刷新（默认等 5 秒），测试会变得很慢
                .timeoutDuration(Duration.ZERO)
                .build();
        return rateLimiterRegistry.replace(name, RateLimiter.of(name, config))
                .orElseGet(() -> rateLimiterRegistry.rateLimiter(name));
    }

    private void rememberAndShrink() {
        originalLoginConfig = rateLimiterRegistry.rateLimiter(LOGIN_LIMITER).getRateLimiterConfig();
        originalPageConfig = rateLimiterRegistry.rateLimiter(PAGE_LIMITER).getRateLimiterConfig();
        replaceWithLimit(LOGIN_LIMITER, 2);
        replaceWithLimit(PAGE_LIMITER, 2);
    }

    /** 把配置恢复成用例开始前的样子（换一个全新实例，窗口也是满的） */
    @AfterEach
    void restoreConfig() {
        if (originalLoginConfig != null) {
            rateLimiterRegistry.replace(LOGIN_LIMITER, RateLimiter.of(LOGIN_LIMITER, originalLoginConfig));
        }
        if (originalPageConfig != null) {
            rateLimiterRegistry.replace(PAGE_LIMITER, RateLimiter.of(PAGE_LIMITER, originalPageConfig));
        }
    }

    // ================================================================
    //  一、登录接口：配额用完 -> 429
    // ================================================================

    @Test
    @DisplayName("① 登录接口配额用完 -> 第 3 次返回 HTTP 429 + code 429")
    void login_beyondLimit_shouldReturn429() throws Exception {
        rememberAndShrink();

        String body = "{\"username\":\"ratelimit_probe\",\"password\":\"whatever\"}";

        // 前两次放行（登录本身会失败，但那是"进了业务"，说明没被限流拦下）
        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultCode.BAD_CREDENTIALS.getCode()));
        }

        // 第 3 次：配额用完，被 Resilience4j 的切面拦下
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())                  // HTTP 429
                .andExpect(jsonPath("$.code").value(ResultCode.TOO_MANY_REQUESTS.getCode()));
    }

    @Test
    @DisplayName("② 前两次的内容仍然是正常的业务响应（限流不该改变正常路径）")
    void login_withinLimit_shouldBehaveNormally() throws Exception {
        rememberAndShrink();

        // 这条守的是"别拦过头"：配额没满时，请求必须原样进到业务逻辑里，
        // 返回的是业务错误（用户名或密码错误），而不是被限流。
        // 如果限流配置写错（比如配额是 0），这里会立刻变成 429。
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_CREDENTIALS.getCode()));
    }

    // ================================================================
    //  二、文章列表接口
    // ================================================================

    @Test
    @DisplayName("③ 文章列表同样受限流保护 -> 配额用完返回 429")
    void articlePage_beyondLimit_shouldReturn429() throws Exception {
        rememberAndShrink();

        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(get("/article/page"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        mockMvc.perform(get("/article/page"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ResultCode.TOO_MANY_REQUESTS.getCode()));
    }

    @Test
    @DisplayName("④ 重置配额之后就恢复（说明限流是「窗口内计数」而不是「永久封禁」）")
    void afterReset_shouldRecover() throws Exception {
        rememberAndShrink();

        // 先把配额打满
        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(get("/article/page")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/article/page")).andExpect(status().isTooManyRequests());

        // reset() 相当于"窗口过去了"（真实场景里是时间到了自动刷新配额）
        // 重开一个窗口（真实场景里是"时间到了，配额自动刷新"；
        // 这里用 changeLimitForPeriod 立刻模拟那一刻，不用真等一分钟）
        replaceWithLimit(PAGE_LIMITER, 2);

        mockMvc.perform(get("/article/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ================================================================
    //  三、配置本身
    // ================================================================

    @Test
    @DisplayName("⑤ 两个限流实例都真的被创建了（防止注解名字写错导致限流静默失效）")
    void limiters_shouldBeRegistered() {
        // 【为什么这条值得单独测】
        //   @RateLimiter(name=...) 里的名字如果和配置里的实例名对不上，
        //   Resilience4j 的行为取决于配置（可能创建默认实例、也可能抛错）。
        //   最糟的情况是"用了默认配置"——额度是默认值，跟我们在
        //   application.properties 里精心定的数字完全无关，而且不会报错。
        //   所以直接断言"这两个名字的实例存在，且配额不是默认值"。
        RateLimiter login = rateLimiterRegistry.rateLimiter(LOGIN_LIMITER);
        RateLimiter page = rateLimiterRegistry.rateLimiter(PAGE_LIMITER);

        org.junit.jupiter.api.Assertions.assertNotNull(login);
        org.junit.jupiter.api.Assertions.assertNotNull(page);

        // 登录的配额必须明显小于列表的配额 —— 这是配置里刻意的差别
        // （登录是暴力破解入口要卡死；列表是最热的读接口要放宽）
        org.junit.jupiter.api.Assertions.assertTrue(
                login.getRateLimiterConfig().getLimitForPeriod()
                        < page.getRateLimiterConfig().getLimitForPeriod(),
                "登录的配额应当小于文章列表的配额，实际 登录="
                        + login.getRateLimiterConfig().getLimitForPeriod()
                        + " 列表=" + page.getRateLimiterConfig().getLimitForPeriod());

        // 两个都必须是"立刻拒绝"而不是让请求排队等待
        org.junit.jupiter.api.Assertions.assertEquals(Duration.ZERO,
                login.getRateLimiterConfig().getTimeoutDuration(),
                "配额用完应当立刻拒绝，而不是让请求等待（等待只会把压力转成线程堆积）");
        org.junit.jupiter.api.Assertions.assertEquals(Duration.ZERO,
                page.getRateLimiterConfig().getTimeoutDuration());
    }
}
