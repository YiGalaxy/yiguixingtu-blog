package com.yigalaxy.yiguixingtu;

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
     * MySQL 容器。
     *
     * 【镜像 tag 为什么写 mysql:8 而不是 mysql:8.4？】
     *   和 docker-compose.yaml 保持一致。测试库与开发库用同一个 tag，
     *   才不会出现"测试全绿、本地一跑就挂"的版本漂移。
     *   （正式部署时会和 compose 一起锁定到具体小版本，见 TECH_ROADMAP 3.3）
     *
     * 【为什么不需要手动建表？】
     *   容器起来是个空库，Flyway 会在应用启动时自动执行
     *   db/migration/V1__init.sql 把表建好（见上一个提交 1.1）。
     *   也就是说：测试验证的就是生产环境真正会跑的那份建表脚本。
     */
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8")
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
            new GenericContainer<>("redis:7")
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
