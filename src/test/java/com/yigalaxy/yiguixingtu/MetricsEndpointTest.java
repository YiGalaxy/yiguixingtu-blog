package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.article.cache.ArticleViewCounter;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.article.task.ViewCountSyncTask;
import com.yigalaxy.yiguixingtu.common.metrics.BusinessMetrics;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * Prometheus 指标端点测试（/actuator/prometheus）
 *
 * 【为什么必须要有一个测试去"访问"这个端点】
 *   引入指标这套东西有一个很坑的失败模式：少一个依赖、
 *   或者自动配置没生效时，应用【编译通过、启动成功、什么错都不报】，
 *   只是那个端点是 404。这个项目已经在 Flyway 和链路追踪上踩过两遍同样的坑
 *   （Boot 4 把自动配置拆成了独立 artifact，少一个就是静默失效）。
 *   所以"启动没报错"不能当证据，必须真的去访问一次、看里面有没有内容。
 *
 * 【为什么断言的是"具体指标名"而不是"返回了 200"】
 *   返回 200 只能说明端点在，说明不了"我们关心的指标真的被采集了"。
 *   比如 http_server_requests_seconds 这条，它依赖 Observation（观测）机制 ——
 *   而本项目正好在链路追踪那件事上怀疑 Observation 没生效
 *   （见 TECH_ROADMAP §9 M5 的 5.1 卡点）。
 *   如果这条指标不出现，那就不只是"少个指标"，而是找到了 5.1 的线索。
 *   所以下面每条断言都点名一个指标，而不是笼统地"有内容"。
 *
 * 【这个类用到的东西】
 *   · {@code MeterRegistry} —— 直接查内存里的指标（不走 HTTP），
 *     用来验证"某个动作有没有让对应的计数器涨"
 *   · MockMvc —— 真实走一遍过滤链去请求端点。
 *     注意这里能测到 /actuator/prometheus，是因为它和业务接口【同端口】。
 *     如果将来把管理端点挪到 management.server.port 上，
 *     这个测试就必须改成真发 HTTP（MockMvc 不走端口，测不到子上下文）。
 * =====================================================================
 */
class MetricsEndpointTest extends AbstractIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private BusinessMetrics metrics;

    @Autowired
    private ArticleViewCounter viewCounter;

    @Autowired
    private ViewCountSyncTask viewCountSyncTask;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    // =================================================================
    //  端点本身
    // =================================================================

    @Test
    @DisplayName("GET /actuator/prometheus -> 200，且是 Prometheus 的文本格式")
    void prometheusEndpoint_shouldBeExposedInPrometheusFormat() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                // 用 contentType 而不是字符串包含来判断格式：Prometheus 的抓取格式
                // 必须是 text/plain，且带 version 参数。有些客户端（以及 Prometheus 自己）
                // 会依赖这个头来解析，格式不对会直接抓取失败。
                .andExpect(content().contentTypeCompatibleWith("text/plain"));
    }

    @Test
    @DisplayName("端点里必须出现 JVM / HTTP 接口 / 连接池这三类【框架自带】的指标")
    void prometheusEndpoint_shouldContainFrameworkMetrics() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // ① JVM —— 装了 micrometer 之后必然有；没有的话说明 registry 根本没接上
        assertTrue(body.contains("jvm_memory_used_bytes"),
                "应当有 JVM 内存指标，缺失说明 micrometer-registry-prometheus 没生效");

        // ② HTTP 接口耗时 —— 这条依赖 Observation，是 5.1 那条线索的间接验证。
        //    先随便请求一个接口，保证这条指标至少被创建过一次
        //    （指标是按需创建的：没有任何请求时它根本不会出现）。
        mockMvc.perform(get("/article/page"));
        String afterRequest = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(afterRequest.contains("http_server_requests"),
                "应当有 HTTP 接口的请求指标。如果这条缺失，说明 Observation 没有为 HTTP 请求创建观测 —— "
                        + "这与 TECH_ROADMAP §9 M5 里 5.1（traceId 拿不到）很可能是同一个根因");

        // ③ 数据库连接池 —— 用它证明"连上了真实的库"，而不是一个空壳 registry
        assertTrue(body.contains("hikaricp_connections"),
                "应当有 HikariCP 连接池指标");
    }

    @Test
    @DisplayName("GET /actuator/health -> 200 且 status 是 UP（容器探针靠它判活）")
    void healthEndpoint_shouldBeUpForContainerProbe() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UP")));
    }

    @Test
    @DisplayName("没有开的路由端点不应该被顺手暴露出去（用管理员身份验证，排除权限干扰）")
    void otherActuatorEndpoints_shouldNotBeExposed() throws Exception {
        // 只 expose 了 health 和 prometheus。这里抽查两个"如果有就说明配置写错了"的端点：
        //   · env   —— 会把配置属性（包括部分环境变量）吐出来
        //   · beans —— 会把整个 Spring 容器的 Bean 列表吐出来
        // 它们不是"用不上"，而是"暴露了就等于把内部结构告诉别人"。
        //
        // 【为什么必须带着管理员 token 去请求，而不是直接匿名请求】
        //   第一版是匿名请求、断言 404，结果拿到 401 —— 因为 Spring Security 的
        //   anyRequest().authenticated() 在【路由之前】就把它拦掉了，
        //   请求根本没走到"这个端点存不存在"那一步。
        //   于是 401 同时对应两种完全不同的情况：
        //     ⓐ 端点没开（安全）  ⓑ 端点开着、只是要登录（有风险）
        //   断言 401 等于把这两种情况混在一起，测了个寂寞。
        //
        // 【为什么不断言 HTTP 404 —— 这一步又踩了一次】
        //   带上 token 之后权限不再是障碍，本以为会拿到 404，结果拿到的是
        //   HTTP 200 + {"code":500,"message":"服务器内部错误"}。
        //   原因是这个项目有一条刻意的约定（见 README「统一返回与错误处理」）：
        //   所有异常都转成 HTTP 200 + body 里的 code，
        //   而"找不到端点"抛出的 NoResourceFoundException 也落进了兜底的
        //   @ExceptionHandler(Exception.class)，于是变成了 code 500。
        //   也就是说：在这个项目里，HTTP 状态码【不能】用来判断"端点存不存在"。
        //
        //   所以断言改成"响应体里没有 env / beans 的数据"。
        //   这样写的另一个好处是它跟错误码约定解耦：
        //   将来 5.6 真把状态码统一成 404 了，这条用例不用改也依然成立。
        String token = loginAsAdminAndGetToken();

        mockMvc.perform(get("/actuator/env").header("Authorization", "Bearer " + token))
                .andExpect(content().string(not(containsString("propertySources"))));

        mockMvc.perform(get("/actuator/beans").header("Authorization", "Bearer " + token))
                .andExpect(content().string(not(containsString("\"beans\""))));
    }

    // =================================================================
    //  自定义业务指标：通过"真的做一次业务动作"来验证
    // =================================================================

    @Test
    @DisplayName("登出真的作废了 token -> token 被拉黑的计数 +1")
    void logout_shouldIncreaseTokenRevokedCounter() throws Exception {
        long before = tokenRevokedCount();

        String token = loginAsAdminAndGetToken();
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertEquals(before + 1, tokenRevokedCount(),
                "登出成功应当让 yiguixingtu.auth.token.revoked 涨 1");
    }

    @Test
    @DisplayName("密码错误 -> 登录失败计数 +1，且 reason 标签是异常类名")
    void loginFailure_shouldIncreaseLoginCounterWithReasonTag() throws Exception {
        // 【这条断言在验证一件容易被想当然的事】
        //   统计是靠监听 Spring Security 的认证事件实现的，
        //   而"事件到底有没有被发出来"取决于 AuthenticationManager 上
        //   有没有挂 AuthenticationEventPublisher。Boot 会自动配一个，
        //   但"应该会"不等于"真的会"—— 所以这里真的登录失败一次，看计数涨没涨。
        //   事件没发出来的话，这条用例会红，那就要退回"在登录接口里显式统计"。
        double before = loginCount("failure", "BadCredentialsException");

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"metrics_probe_user\",\"password\":\"definitely-wrong\"}"))
                // 【为什么这里期望 200，而不是 401】
                //   这是本项目一条刻意的约定（见 README「统一返回与错误处理」）：
                //   业务错误一律 HTTP 200 + body 里带 code。
                //   真正的错误码在下面那句 jsonPath("$.code") 里断言 ——
                //   第一版这里写的是 status().isUnauthorized()，结果拿到了 200，
                //   一开始还以为是异常处理坏了，其实是断言写错了。
                //   这也说明：断言要跟着"项目的约定"走，不能跟着"直觉上应该怎样"走。
                .andExpect(status().isOk())
                // 登录失败的业务码是 400（ResultCode.BAD_CREDENTIALS），不是 401。
                // 401 在本项目里是 UNAUTHORIZED —— "没带 token / token 无效"，
                // 和"密码错了"是两件事：前者该让前端弹登录框，后者该提示密码错。
                // 一开始按直觉写了 401，跑出来是 400，才发现这两个码本来就该分开。
                .andExpect(jsonPath("$.code").value(400));

        double after = loginCount("failure", "BadCredentialsException");
        assertEquals(before + 1.0, after, 0.001,
                "密码错误应当让登录失败计数 +1（标签 reason=BadCredentialsException）");
    }

    @Test
    @DisplayName("浏览量落库 -> 落库总量与涉及文章数的计数都涨，且耗时被记录")
    void viewSync_shouldRecordCountersAndTimer() {
        // 造一篇文章，然后走真实的"详情页访问 → Redis 计数 → 定时落库"这条链路
        Long articleId = seedPublishedArticle();
        viewCounter.increment(articleId);
        viewCounter.increment(articleId);
        viewCounter.increment(articleId);

        double flushedBefore = counterValue("yiguixingtu.article.views.flushed");
        double articlesBefore = counterValue("yiguixingtu.article.views.synced.articles");

        viewCountSyncTask.syncViewCounts();

        // 涨了 3 —— 因为上面 INCR 了 3 次。
        // 【为什么敢断言"正好 3"而不是"大于 0"】
        //   syncViewCounts 会把【所有】待落库的增量都带走，
        //   而这条用例跑在事务里、其它用例造的数据已经回滚，
        //   所以这一次同步处理的就应该是刚才那 3 次。
        //   如果这里是"大于 0"，那么"增量被漏掉一部分"这种 bug 就测不出来了。
        assertEquals(flushedBefore + 3.0, counterValue("yiguixingtu.article.views.flushed"), 0.001,
                "三次访问应当让落库总量涨 3");
        assertEquals(articlesBefore + 1.0, counterValue("yiguixingtu.article.views.synced.articles"), 0.001,
                "本次只涉及 1 篇文章");

        // 计时器：count 就是"被计时了几次"，至少要有刚才这一次
        assertNotNull(meterRegistry.find("yiguixingtu.article.views.sync").timer(),
                "批量落库应当记录耗时（yiguixingtu.article.views.sync）");
        assertTrue(meterRegistry.find("yiguixingtu.article.views.sync").timer().count() >= 1,
                "计时器至少要记录到刚才这一次同步");
    }

    @Test
    @DisplayName("待落库数量是一个即时值（Gauge），同步后应当归零")
    void pendingViews_shouldBeReportedAsGauge() {
        Long articleId = seedPublishedArticle();
        viewCounter.increment(articleId);

        viewCountSyncTask.syncViewCounts();

        // gauge 是"取走之后清空"的语义：同步刚跑完，此时没有文章在堆积
        assertEquals(0.0, counterValue("yiguixingtu.article.views.pending"), 0.001,
                "同步完成后待落库文章数应当归零");
    }

    // =================================================================
    //  工具方法
    // =================================================================

    /** 取某个"无标签"计数器的当前值；不存在时返回 0 */
    private double counterValue(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** 取登录计数器的当前值（带 result / reason 两个标签） */
    private double loginCount(String result, String reason) {
        Counter counter = meterRegistry.find("yiguixingtu.auth.login")
                .tag("result", result)
                .tag("reason", reason)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long tokenRevokedCount() {
        return (long) counterValue("yiguixingtu.auth.token.revoked");
    }

    /**
     * 用管理员账号登录并拿到 token。
     *
     * 【为什么在用例里现造一个管理员，而不是复用某个固定账号】
     *   测试类跑在事务里、用完即回滚，不去依赖"库里原本有谁"，
     *   这样单独跑这个类、或者调整其它测试的数据都不会互相影响。
     */
    private String loginAsAdminAndGetToken() throws Exception {
        String username = "metrics_probe_" + System.nanoTime();
        User admin = new User();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode("probe-password"));
        admin.setNickname("指标测试管理员");
        admin.setRole("ADMIN");
        admin.setStatus(1);
        userMapper.insert(admin);

        String body = mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"probe-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 从返回的 JSON 里抠出 token。用字符串查找而不是引入一个 JSON 解析器：
        // 这个测试只关心"拿到 token"，用 JSONPath 之类反而多一层依赖。
        int idx = body.indexOf("\"token\":\"");
        assertTrue(idx > 0, "登录响应里应当带 token，实际响应=" + body);
        int start = idx + "\"token\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    /**
     * 造一篇已发布的文章。
     *
     * 【为什么用 JdbcTemplate 风格的 Mapper 而不是 Service】
     *   这里只需要"库里有这么一行"，不需要校验、摘要生成那些业务规则；
     *   走 Service 反而会把无关逻辑拉进来。浏览量这条链路本身
     *   （ArticleViewCountTest）已经有完整的接口级用例覆盖了。
     */
    private Long seedPublishedArticle() {
        Article article = new Article();
        article.setTitle("指标测试文章 " + System.nanoTime());
        article.setSummary("用于验证浏览量落库指标");
        article.setContent("正文");
        article.setStatus(1);
        article.setIsTop(0);
        article.setViewCount(0);
        article.setAuthorId(1L);
        articleMapper.insert(article);
        return article.getId();
    }
}
