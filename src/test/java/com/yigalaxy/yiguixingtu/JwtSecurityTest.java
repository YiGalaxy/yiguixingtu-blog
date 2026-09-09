package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试 JWT 安全链路：没token、合法token、非法token 三种情况
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
class JwtSecurityTest {

    @Autowired
    private MockMvc mockMvc;          // 模拟发送HTTP请求
    @Autowired
    private JwtUtil jwtUtil;          // 用来生成合法token

    /** ① 没带token访问受保护接口 → 应该 401 */
    @Test
    void noToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/test/protected"))
                .andExpect(status().isUnauthorized());
    }

    /** ② 带合法token访问 → 应该 200 */
    @Test
    void validToken_shouldReturn200() throws Exception {
        String token = jwtUtil.generateToken(1L, "yigalaxy", "ADMIN");
        mockMvc.perform(get("/test/protected")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** ③ 带非法token → 应该 401 */
    @Test
    void invalidToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/test/protected")
                        .header("Authorization", "Bearer not.a.valid.token"))
                .andExpect(status().isUnauthorized());
    }

    /** 注册一个测试用的控制器，提供 /test/protected 这个"需要登录"的接口 */
    @TestConfiguration
    static class TestConfig {
        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {
        @GetMapping("/test/protected")
        public String protectedEndpoint() {
            return "OK";
        }
    }
}