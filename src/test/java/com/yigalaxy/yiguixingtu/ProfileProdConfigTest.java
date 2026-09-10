package com.yigalaxy.yiguixingtu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 生产环境（prod）的配置行为测试
 *
 * 【为什么要专门跑一遍 prod】
 *   prod 和 dev 的差异全在"上线才生效"的那几项：
 *   接口文档必须关、SQL 日志必须关、跨域必须是真实域名。
 *   如果只用 dev 跑测试，这些差异永远没有被执行过 ——
 *   等于把"上线当天才发现"的风险留在那里。
 *
 * 【怎么让测试跑在 prod 上】
 *   两个注解配合：
 *     · {@code @ActiveProfiles("prod")} —— 激活 prod profile，
 *       Spring 会把 application-prod.properties 叠加进来
 *     · {@code @TestPropertySource} —— 补上 application-prod.properties 里
 *       那些【故意不给默认值】的占位符（DB_URL / REDIS_PASSWORD 等）
 *
 *   ⚠️ 这里为什么要补？因为 prod 的配置【故意】不给凭据默认值：
 *      忘配环境变量时服务应该启动失败，而不是带着一个公开的默认密码悄悄跑起来。
 *      所以测试里必须把这些占位符显式喂进去，否则上下文根本起不来。
 *      —— 这本身就是那条设计在起作用的一次演示。
 *
 *   真正的数据库和 Redis 仍然用 Testcontainers 起的容器：
 *   基类的 @DynamicPropertySource 优先级比配置文件高，
 *   所以无论哪个 profile，最终连的都是容器，不会误连生产库。
 *   （这条很重要：否则这个测试就变成"用测试去连真库"了）
 * =====================================================================
 */
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        // 下面几个只是让占位符能解析，实际连接地址由 @DynamicPropertySource 覆盖
        "DB_URL=jdbc:mysql://placeholder:3306/yiguixingtu",
        "DB_USERNAME=placeholder",
        "DB_PASSWORD=placeholder",
        "REDIS_HOST=placeholder",
        "REDIS_PASSWORD=placeholder"
})
class ProfileProdConfigTest extends AbstractIntegrationTest {

    /** 模拟发 HTTP 请求用（基类只开启了 @AutoConfigureMockMvc，实例要各自注入） */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Value("${mybatis-plus.configuration.log-impl}")
    private String sqlLogImpl;

    @Value("${springdoc.api-docs.enabled}")
    private String apiDocsEnabled;

    @Test
    @DisplayName("prod 环境：接口文档必须关掉（Swagger 等于给攻击者一份现成的接口说明书）")
    void apiDocs_underProdProfile_shouldBeDisabled() throws Exception {
        assertTrue("false".equalsIgnoreCase(apiDocsEnabled),
                "prod 下 springdoc.api-docs.enabled 应当是 false，实际=" + apiDocsEnabled);

        // 光看配置项还不够，直接请求一次确认它【真的】访问不到：
        // /v3/api-docs 现在不在 SecurityConfig 的 permitAll 名单里，
        // 所以未登录访问会落到 anyRequest().authenticated() 上得到 401
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("prod 环境：SQL 日志必须关掉（既拖慢响应，又把数据写进日志）")
    void sqlLog_underProdProfile_shouldBeDisabled() {
        assertTrue(sqlLogImpl.contains("NoLoggingImpl"),
                "prod 下不应打印 SQL，实际配置=" + sqlLogImpl);
    }

    @Test
    @DisplayName("prod 环境：未配置凭据时应当【启动失败】，而不是用默认值悄悄跑起来")
    void prodProfile_shouldRefuseToStartWithoutCredentials() {
        // 这条用例是在验证一条"设计意图"而不是功能：
        // application-prod.properties 里 DB_PASSWORD 写的是 ${DB_PASSWORD}，
        // 一个默认值都不给。所以只要有人手贱加上 `:root` 这种默认值，
        // 这条用例就会失败 —— 提醒他"你刚刚把一道安全防线拆了"。
        //
        // 判断方式：读配置文件原文，确认这几个占位符确实是"裸的"（没有 :默认值）。
        String prodConfig = readClasspathFile("application-prod.properties");

        for (String key : new String[]{"DB_URL", "DB_USERNAME", "DB_PASSWORD", "REDIS_HOST", "REDIS_PASSWORD"}) {
            assertTrue(prodConfig.contains("${" + key + "}"),
                    "prod 配置里 " + key + " 必须是裸占位符 ${" + key + "}（不给默认值），"
                            + "否则忘配环境变量时会带着默认凭据悄悄上线");
            assertTrue(!prodConfig.contains("${" + key + ":"),
                    "prod 配置里 " + key + " 不允许写默认值（发现 ${" + key + ":...}）");
        }
    }

    /** 读取 classpath 下的文本文件（用来检查配置文件原文，而不是检查解析结果） */
    private String readClasspathFile(String name) {
        try (var in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("classpath 下找不到 " + name);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 " + name + " 失败", e);
        }
    }
}
