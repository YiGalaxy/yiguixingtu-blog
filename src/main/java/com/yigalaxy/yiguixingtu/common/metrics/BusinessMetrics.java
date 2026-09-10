package com.yigalaxy.yiguixingtu.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * =====================================================================
 * 业务指标 —— 把"我们自己代码里的关键动作"变成 Prometheus 能抓的数字
 *
 * ============ 为什么需要这一层，automatic 指标不够吗 ============
 *
 * 装上 micrometer-registry-prometheus 之后，下面这些是【白送】的：
 *   · jvm_memory_used_bytes / jvm_gc_* —— JVM 内存与 GC
 *   · http_server_requests_seconds      —— 每个接口的请求量与耗时分布
 *   · hikaricp_connections_*            —— 数据库连接池的活跃/空闲连接
 *   · tomcat_threads_*                  —— Web 容器线程池
 *
 * 但它们只知道"框架层面发生了什么"，不知道"业务层面发生了什么"。
 * 一个具体例子：浏览量落库失败时，
 *   · http_server_requests 一切正常（详情页照样 200）
 *   · jvm / 连接池 也一切正常
 * 可 Redis 里的增量正在悄悄堆积、数据库里的浏览量和实际访问量越差越远。
 * 这类"业务上出事了但技术指标全绿"的情况，只能靠业务指标发现 ——
 * 这就是这个类存在的理由。
 *
 * ============ 为什么指标名和标签集中定义在这个类里 ============
 *   同一个指标如果在两个地方各写一遍名字（"yiguixingtu.article.views.flushed"
 *   和 "yiguixingtu.article.view.flushed"），Prometheus 里就是两条互不相干的曲线，
 *   看板会对不上、而且这种错很难发现。集中定义 = 只有一个地方能写错。
 *
 * ============ 用到的技术栈 ============
 *   · {@link MeterRegistry} —— Micrometer 的门面。它不认识 Prometheus，
 *     只认识"Counter / Gauge / Timer"这些抽象；具体怎么输出成文本，
 *     由注册进去的 registry 实现决定（我们装的是 Prometheus 那个）。
 *     这样做的好处是：以后要换成别的监控系统，业务代码一行都不用改。
 *   · {@link Counter} —— 只增不减的累计值（请求失败次数、落库总量）。
 *     注意它【不是】"当前值"：要看当前速率得用 Prometheus 的 rate() 函数。
 *   · {@link Gauge} —— 可增可减的瞬时值（连接数、待处理数量）。
 *   · {@link Timer} —— 既记次数又记耗时分布（P50/P99 都能算出来）。
 *
 * ============ 一条重要的纪律：标签不能放"会无限增长的值" ============
 *   标签（tag）的每个不同取值都会变成一条独立的时间序列。
 *   如果把【用户名】或者【文章 ID】做成标签，序列数量就随用户数/文章数无限增长，
 *   内存和 Prometheus 存储都会被拖垮。这叫"标签基数爆炸"。
 *   所以下面所有的标签取值都是【有限枚举】：success/failure、异常类名。
 *   用户名只出现在日志里，绝不出现在指标标签里。
 * =====================================================================
 */
@Component
public class BusinessMetrics {

    // ============ 指标名 ============
    // 统一加 yiguixingtu. 前缀，避免和框架自带的指标重名。
    // 注意 Micrometer 里的点号 "." 在 Prometheus 输出时会变成下划线 "_"，
    // 所以这里的 yiguixingtu.article.views.flushed
    // 在 /actuator/prometheus 里看到的是 yiguixingtu_article_views_flushed_total

    /** 累计落库的浏览量增量（次）。用它观察"每天到底有多少次浏览被写进了库" */
    private static final String VIEWS_FLUSHED = "yiguixingtu.article.views.flushed";

    /** 累计落库涉及的文章数（篇）。和上面那个的区别：一篇被看了 100 次算 1 篇、100 次 */
    private static final String VIEWS_SYNCED_ARTICLES = "yiguixingtu.article.views.synced.articles";

    /** 当前待落库的文章数。取的是【最近一次同步时】的值，见下面 gauge 的说明 */
    private static final String VIEWS_PENDING = "yiguixingtu.article.views.pending";

    /** 每次批量落库的耗时。P99 突然变高 = 数据库这边开始吃力了 */
    private static final String VIEWS_SYNC_TIMER = "yiguixingtu.article.views.sync";

    /** 登录次数，标签 result=success|failure */
    private static final String LOGIN = "yiguixingtu.auth.login";

    /** 被拉黑的 token 数（也就是"真的生效了的登出次数"） */
    private static final String TOKEN_REVOKED = "yiguixingtu.auth.token.revoked";

    private static final String TAG_RESULT = "result";
    private static final String TAG_REASON = "reason";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";

    private final MeterRegistry registry;

    /**
     * "最近一次同步时待落库的文章数"。
     *
     * 【为什么用 AtomicLong 而不是在采集时去问 Redis】
     *   Gauge 的取值发生在【Prometheus 来抓的那一刻】（每 15~60 秒一次）。
     *   最直白的实现是在这里面去 Redis 数一下 key 的个数，
     *   但那就要跑一条 KEYS —— KEYS 会阻塞 Redis（它要扫整个 keyspace）。
     *   让一个后台的监控采集动作去阻塞生产 Redis，是本末倒置。
     *   所以改成：由定时任务在同步时把这个值记下来，采集时只是读一个内存里的数字。
     *   代价是它反映的是"上次同步时"的情况，会滞后一个同步周期 ——
     *   对"有没有在堆积"这个判断来说完全够用。
     *
     * 【为什么要显式持有这个引用】
     *   Gauge 只持有弱引用。如果这个 AtomicLong 是个局部变量、被 GC 掉，
     *   指标就会莫名变成 NaN。写成字段是最简单可靠的解法。
     */
    private final AtomicLong pendingArticles = new AtomicLong(0L);

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Gauge 在构造时注册一次就够了 —— 它每次采集都会去读那个 AtomicLong 的当前值，
        // 不需要（也不能）每次采集都重新 register。
        Gauge.builder(VIEWS_PENDING, pendingArticles, AtomicLong::get)
                .description("最近一次批量落库时，Redis 里还在累计增量的文章数")
                .register(registry);
    }

    // =================================================================
    //  浏览量落库
    // =================================================================

    /**
     * 取"批量落库耗时"这个计时器。
     *
     * 【为什么返回 Timer 让调用方自己计时，而不是在这里包一个 record(Runnable)】
     *   落库那段逻辑是 @Transactional 的，把它包成一个 lambda 传进来，
     *   容易出现"事务边界在 lambda 里面还是外面"这种含糊；
     *   而 Timer 本身提供了 {@code record(Runnable)}，
     *   调用方用起来同样是一行。所以这里只负责"把尺子递过去"。
     */
    public Timer viewSyncTimer() {
        return Timer.builder(VIEWS_SYNC_TIMER)
                .description("一次批量落库（Redis 增量 → MySQL）的耗时")
                .register(registry);
    }

    /**
     * 记一次批量落库的结果。
     *
     * @param articles  本次处理了几篇文章（也就是当时待落库的文章数）
     * @param flushedViews 本次一共落库了多少次浏览（各篇文章增量之和）
     */
    public void recordViewSync(int articles, long flushedViews) {
        pendingArticles.set(articles);

        if (articles > 0) {
            Counter.builder(VIEWS_SYNCED_ARTICLES)
                    .description("累计落库涉及的文章数")
                    .register(registry)
                    .increment(articles);
        }
        if (flushedViews > 0) {
            Counter.builder(VIEWS_FLUSHED)
                    .description("累计落库的浏览量增量")
                    .register(registry)
                    .increment(flushedViews);
        }
    }

    // =================================================================
    //  认证
    // =================================================================

    /** 记一次登录成功 */
    public void recordLoginSuccess() {
        loginCounter(RESULT_SUCCESS, null).increment();
    }

    /**
     * 记一次登录失败。
     *
     * @param reason 失败原因，取值是【异常类名】（有限的几个：
     *               BadCredentialsException / DisabledException /
     *               InternalAuthenticationServiceException ...）。
     *               ⚠️ 刻意不把用户名做成标签 —— 那是无界基数，见类注释。
     *               也刻意不带密码相关的任何信息，哪怕只是长度。
     */
    public void recordLoginFailure(String reason) {
        loginCounter(RESULT_FAILURE, reason).increment();
    }

    /** 记一次"token 被真正拉黑"（也就是登出真的生效了，而不是空转） */
    public void recordTokenRevoked() {
        Counter.builder(TOKEN_REVOKED)
                .description("被加入黑名单的 token 数（登出真正生效的次数）")
                .register(registry)
                .increment();
    }

    /**
     * 构造（或取出）登录计数器。
     *
     * 【为什么失败时才有 reason 标签、成功时没有】
     *   成功只有一种，加一个 reason=none 只是噪音；
     *   而失败的原因值得区分 —— "密码错"占绝大多数是正常的，
     *   但如果 DisabledException 突然涨起来，说明有人在撞已经被禁用的账号。
     */
    private Counter loginCounter(String result, String reason) {
        Counter.Builder builder = Counter.builder(LOGIN)
                .description("登录次数")
                .tag(TAG_RESULT, result);
        if (reason != null) {
            builder.tag(TAG_REASON, reason);
        }
        return builder.register(registry);
    }
}
