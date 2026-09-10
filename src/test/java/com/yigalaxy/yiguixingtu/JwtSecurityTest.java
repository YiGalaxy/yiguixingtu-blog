package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试 JWT 安全链路：没token、合法token、非法token、token对应用户不存在。
 *
 * 【为什么要自己造用户，而不是写死 userId = 1？】
 * JwtAuthenticationFilter 现在会拿 token 里的 userId 回数据库查一次，
 * 确认用户"仍然存在且未被禁用"才给认证。所以：
 *   · 写死 userId=1 -> 测试就依赖"数据库里恰好存在 id=1 这个可用用户"，
 *     哪天这个用户被删了 / 被禁用了，测试会莫名其妙失败，而且很难查原因；
 *   · 自己插一个用户、拿它【真实的 id】签发 token -> 测试自给自足、
 *     可以在任何数据库环境下重复运行。
 *   ★ 这一点在本类改成容器化之后更加重要：Testcontainers 起的库里
 *     本来就只有 Flyway 建的空表，任何"依赖库里已有数据"的写法都会立刻暴露。
 *
 * @Transactional 让插入的测试用户在每个用例结束后自动回滚，不污染数据库。
 */
@Slf4j
class JwtSecurityTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;          // 模拟发送HTTP请求
    @Autowired
    private JwtUtil jwtUtil;          // 用来生成合法token
    @Autowired
    private UserMapper userMapper;    // 用来插入测试用户
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 本测试专用的用户，以及用它真实 id 签发的合法 token */
    private User testUser;
    private String validToken;

    @BeforeEach
    void setUp() {
        // 插一个测试用户（insert 之后 MyBatis-Plus 会把自增主键回填到对象里）
        testUser = new User();
        testUser.setUsername("test_jwt_user");
        testUser.setPassword(passwordEncoder.encode("123456"));
        testUser.setNickname("JWT测试用户");
        testUser.setRole("ADMIN");
        testUser.setStatus(1);
        testUser.setDeleted(0);
        userMapper.insert(testUser);

        // 用【真实的 userId】签发 token —— 不再写死 1L
        validToken = jwtUtil.generateToken(testUser.getId(), testUser.getUsername(), testUser.getRole());
        log.info("测试用户已创建: id={}, username={}", testUser.getId(), testUser.getUsername());
    }

    /** ① 没带token访问受保护接口 → 应该 401 */
    @Test
    void noToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/test/protected"))
                .andExpect(status().isUnauthorized());
    }

    /** ② 带合法token访问 → 应该 200 */
    @Test
    void validToken_shouldReturn200() throws Exception {
        mockMvc.perform(get("/test/protected")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk());
    }

    /** ③ 带非法token → 应该 401 */
    @Test
    void invalidToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/test/protected")
                        .header("Authorization", "Bearer not.a.valid.token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * ④ token 签名和有效期都没问题，但它指向的用户在数据库里不存在 → 应该 401
     *
     * 这一条专门验证本次新增的防护：以前过滤器只信 token，不看数据库，
     * 所以"用户已被删除但仍持有旧 token"能照样访问。
     * 现在过滤器会回查数据库，查不到人就不给认证。
     */
    @Test
    void tokenWithNonExistUser_shouldReturn401() throws Exception {
        // 用一个几乎不可能存在的 id 签发一个"格式完全合法"的 token
        String ghostToken = jwtUtil.generateToken(999999999L, "ghost_user", "ADMIN");

        mockMvc.perform(get("/test/protected")
                        .header("Authorization", "Bearer " + ghostToken))
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
