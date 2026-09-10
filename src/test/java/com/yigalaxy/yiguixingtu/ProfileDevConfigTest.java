package com.yigalaxy.yiguixingtu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 开发环境（dev）的配置行为测试
 *
 * 【为什么"配置"也要写测试】
 *   多环境拆分配置最容易出的问题不是编译错误，而是"上线才发现"：
 *   跨域白名单忘了改、Swagger 忘了关、SQL 日志忘了关。
 *   这些在本地全都是好的，只有部署之后才暴露，而那时候排查成本最高。
 *   所以这里把"dev 应该是什么样"和"prod 应该是什么样"都用断言固定下来。
 *
 * 【这类测试断言的是什么】
 *   不是业务逻辑，而是【环境的行为契约】：
 *     · dev：接口文档开着（本地要能点开 Swagger 调试）
 *     · dev：前端 localhost:3000 能跨域调通（否则本地联调直接卡住）
 *     · dev：不在白名单的来源必须被拒（这是安全底线，不能因为开发环境就放松）
 *
 * 【用到的东西】
 *   · {@code @ActiveProfiles} 没有出现在本类上 —— 因为 application.properties
 *     里默认就是 dev，所以本类跑的就是真实的默认环境（这也是它值得测的原因：
 *     别人 clone 下来不加任何参数，跑的就是这一套）
 *   · MockMvc 的 {@code options(...)} —— 模拟浏览器发出的 CORS 预检请求
 *     （真实浏览器在发 PUT/DELETE/带自定义头的请求之前，会先发一个 OPTIONS 问一句
 *      "我能不能这么调你"，后端必须正确回答，否则真正的请求根本不会被发出去）
 * =====================================================================
 */
class ProfileDevConfigTest extends AbstractIntegrationTest {

    /** 模拟发 HTTP 请求用（基类只开启了 @AutoConfigureMockMvc，实例要各自注入） */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Value("${mybatis-plus.configuration.log-impl}")
    private String sqlLogImpl;

    @Value("${springdoc.api-docs.enabled}")
    private String apiDocsEnabled;

    @Test
    @DisplayName("dev 环境：接口文档是开着的（本地要能点开 Swagger 自己调接口）")
    void apiDocs_underDevProfile_shouldBeAccessible() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("dev 环境：SQL 日志是开的（本地排查查不到数据时最有用）")
    void sqlLog_underDevProfile_shouldBeEnabled() {
        assertNotNull(sqlLogImpl);
        assertTrue(sqlLogImpl.contains("StdOutImpl"),
                "dev 环境应当把 SQL 打到控制台，实际配置=" + sqlLogImpl);
    }

    @Test
    @DisplayName("跨域：白名单里的来源可以调用（本地前端跑在 3000，必须放行）")
    void cors_configuredOrigin_shouldBeAllowed() throws Exception {
        mockMvc.perform(options("/article/page")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                // 浏览器就是靠这个响应头决定"放不放行"的，缺了它前端会报跨域错误
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    @DisplayName("跨域：不在白名单的来源必须被拒（开发环境也不能放松这条底线）")
    void cors_unknownOrigin_shouldBeRejected() throws Exception {
        mockMvc.perform(options("/article/page")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                // 注意这里断言的是"没有放行头"这一事实。
                // 只断言"被拒绝"还不够 —— 真正危险的是"放行了但没人发现"
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("健康检查端点未登录也能访问（容器探针要用它，否则容器会被判定 unhealthy 一直被重启）")
    void actuatorHealth_shouldBeAccessibleWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("配置项本身能被解析（防止占位符写错导致启动即失败）")
    void placeholders_shouldResolve() {
        // 这几项都是"写错了要等启动报错才发现"的类型，
        // 用断言把它们钉住：一旦有人改错占位符名，这里立刻变红
        assertTrue(!apiDocsEnabled.isBlank(), "springdoc.api-docs.enabled 应当能解析出值");
    }
}
