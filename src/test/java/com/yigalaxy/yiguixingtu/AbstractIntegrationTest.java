package com.yigalaxy.yiguixingtu;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * =====================================================================
 * 集成测试基类：为所有测试提供一个"自带数据库和 Redis"的运行环境
 *
 * 【这个类解决的问题】
 *   在此之前，8 个测试类都是 @SpringBootTest 直连本机的
 *   MySQL(localhost:3310) 与 Redis(localhost:6380)，带来两个后果：
 *     · 换一台机器 clone 下来，没先 docker compose up -d 就全红
 *     · CI 根本跑不了 —— 项目里最值钱的资产（76 个用例）只能在
 *       自己电脑上演示，别人点开仓库看不到任何验证结果
 *   现在改成：测试启动时自己拉起 MySQL 与 Redis 容器，
 *   跑完自动销毁，对宿主机没有任何要求（只要有 Docker）。
 *
 * 【为什么用 static 代码块启动，而不是 @Testcontainers + @Container】
 *   这是 Testcontainers 里一个很容易踩错的点：
 *     · @Testcontainers 扩展管理的是"每个测试类"的生命周期 ——
 *       每个测试类都会重新 start 一次容器、跑完再 stop
 *     · 而 Spring 的 ApplicationContext 是【跨测试类缓存复用】的，
 *       数据源在第一个测试类时就绑定到了第一个容器的端口上
 *     · 两者一叠加，第二个测试类起来的是新容器（新端口），
 *       但复用的还是老 context —— 连的是一个已经被 stop 掉的容器
 *   写成 static 字段 + static 代码块（业界叫 singleton container pattern），
 *   容器只在 JVM 启动时拉一次，整个测试过程共用一个实例，
 *   8 个测试类共享同一个 ApplicationContext，速度也最快。
 *   容器的回收交给 Testcontainers 的 Ryuk（JVM 退出时自动清理），不用手写 @AfterAll。
 *
 * 【@Transactional 放在基类上】
 *   8 个测试类原本都各自标了 @Transactional，行为完全一致。
 *   提到基类有两个好处：① 不用在每个类里重复；② 让各测试类的
 *   "上下文配置"尽量一致 —— Spring 复用 ApplicationContext 是有条件的，
 *   配置只要差一处就会再起一个 context、再跑一次 Flyway。
 *   （JwtSecurityTest / GlobalExceptionHandlerTest 自带 @TestConfiguration
 *    内嵌测试 Controller，那是它们自己的需要，会各自建 context，属正常。）
 * =====================================================================
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public abstract class AbstractIntegrationTest {

    /**
     * 限流器注册表，用来在每条用例开始前把配额清零。
     *
     * 【为什么这件事必须做 —— 限流器的状态是"全局"的，和数据库不一样】
     *   `@Transactional` 能让每个用例对【数据库】的改动自动回滚，
     *   但拿限流器毫无办法：Resilience4j 的 RateLimiter 是装在
     *   Spring 容器里的一个进程级计数器，测试类之间还会共用同一个
     *   ApplicationContext —— 也就是共用同一批计数器。
     *
     *   后果很具体：登录接口的配额是 5 次/分钟（防暴力破解，见配置），
     *   而 LogoutTokenTest 有 11 条用例、每条都要登录一次。
     *   不重置的话，跑到第 6 条就会开始收到 429，
     *   而且报错信息是"登录失败了"，看起来像认证坏了 —— 极难排查。
     *
     * 【为什么放在基类而不是各个测试类里】
     *   每个继承它的测试类都会自动获得这个行为，不用记得手动加。
     *   这和"在 ArticleCacheTest 里 @BeforeEach 清 Redis key"是同一个思路：
     *   **测试外部状态（Redis、限流器）都要自己负责清理，
     *     因为事务回滚管不到它们。**
     */
    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    /**
     * 把每个限流器换成一个全新的、窗口满血的实例。
     *
     * 【⚠️ 为什么不是 limiter.reset()】
     *   Resilience4j 2.4.0 的 {@code RateLimiter} 接口上【没有 reset() 方法】
     *   （第一版就是照着直觉写了 reset()，编译直接报找不到符号）。
     *   接口上能用的只有 acquirePermission / reservePermission /
     *   drainPermissions / changeLimitForPeriod / changeTimeoutDuration，
     *   其中 drainPermissions 是"把剩余配额全部消耗掉"——正好是反过来的操作。
     *
     * 【⚠️ 为什么也不是 changeLimitForPeriod】
     *   第二版改用 changeLimitForPeriod（把配额重设成同一个值）想刷新窗口，
     *   实测【不可靠】：改完之后 {@code getRateLimiterConfig()} 显示 limit=2，
     *   但 {@code getMetrics().getAvailablePermissions()} 还是 5 ——
     *   配置变了、计数没变。它内部是 updateAndGet 出一个新 State，
     *   而配额计数器沿用旧引用，所以"改配额"并不等于"重新开窗"。
     *
     * 【最终做法：直接换一个新的 RateLimiter 实例】
     *   {@code Registry.replace(name, entry)} 把注册表里的那个换掉，
     *   新实例的计数器是全新的、窗口是满的 —— 语义明确、结果确定。
     *   切面每次调用都从注册表按名字取，所以换掉之后立刻生效。
     */
    @BeforeEach
    void resetRateLimiters() {
        rateLimiterRegistry.getAllRateLimiters().forEach(this::refill);
    }

    /** 用同名同配置的全新实例替换掉注册表里的那个 */
    private void refill(RateLimiter limiter) {
        rateLimiterRegistry.replace(limiter.getName(),
                RateLimiter.of(limiter.getName(), limiter.getRateLimiterConfig()));
    }

    /**
     * MySQL 容器。
     *
     * 【镜像 tag 为什么现在写 mysql:8.4 而不是浮动的 mysql:8】
     *   生产编排（docker-compose.prod.yaml）已经把小版本钉在 8.4 ——
     *   起因是实测踩过：一个浮动的 mysql:8 镜像拉下来竟是 Ver 8.0.27（2021-10 的版本）。
     *   测试容器跟着钉到同一个 minor，才能保证"测试跑的那个版本"和"生产跑的那个版本"
     *   是同一条线；否则等浮动标签某天落到另一个 minor 上，
     *   就会出现"测试全绿、线上起不来"这种最难查的偏差。
     *   （开发用的 docker-compose.yaml 仍是浮动 tag，本地调试要的是方便，不是可复现。）
     *   ⚠️ 这一条以前写的是"和 docker-compose.yaml 保持一致"，见 TECH_ROADMAP 3.3。
     *
     * 【为什么不需要手动建表？】
     *   容器起来是个空库，Flyway 会在应用启动时自动执行
     *   db/migration/V1__init.sql 把表建好（见上一个提交 1.1）。
     *   也就是说：测试验证的就是生产环境真正会跑的那份建表脚本。
     */
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("yiguixingtu")
                    .withUsername("root")
                    // 容器只在测试期间存活，密码用什么都不影响安全；
                    // 写成 test 是为了让人一眼看出"这是测试环境的库"
                    .withPassword("test");

    /**
     * Redis 容器。
     * 用 GenericContainer 而不是专门的 RedisContainer：官方没有 redis 模块，
     * 而 Redis 只需要暴露一个端口，通用容器完全够用。
     */
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4")
                    .withExposedPorts(6379);

    static {
        // 顺序启动：MySQL 初始化比 Redis 慢，先起它可以让两个容器的
        // 就绪等待时间重叠，而不是串行等待
        MYSQL.start();
        REDIS.start();
    }

    /**
     * 把容器的真实地址注入 Spring 配置。
     *
     * 【为什么必须用 @DynamicPropertySource，不能写死在配置文件里？】
     *   容器启动时 Docker 分配的宿主端口是【随机的】（Testcontainers 故意这么做，
     *   避免固定端口和本机已占用的端口打架），所以只能等容器起来之后
     *   再把 getJdbcUrl() / getMappedPort() 的值注册进去。
     *   它是"每次创建 context 前动态求值"，正好满足这个需求。
     *
     * 【注意这里覆盖掉了哪些配置】
     *   覆盖 application.properties 里的 MySQL 地址、账号密码，
     *   以及 Redis 的 host 和 port。其余配置（Flyway、MyBatis-Plus、
     *   JWT 密钥等）沿用主配置 —— 测试要测的是【和生产一样的配置】，
     *   不该为了测试另起一套。
     */
    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
