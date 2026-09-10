package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试全局异常处理器（GlobalExceptionHandler）：
 *  业务异常、未知异常、参数校验失败 是否都被统一转成规范 Result。
 *
 * 【关于 token】这里以前写死 userId=1 签发 token。
 * 现在 JwtAuthenticationFilter 会拿 userId 回数据库查人（确认存在且未禁用），
 * 写死 id 会让测试依赖"数据库里恰好存在 id=1 的可用用户"，很脆弱。
 * 所以改成：每个用例先插一个自己的测试用户，用它的【真实 id】签发 token。
 *
 * @Transactional 保证插入的测试用户测试完自动回滚，不污染数据库。
 */
class GlobalExceptionHandlerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 用本测试自己插入的用户签发的合法 token */
    private String validToken;

    @BeforeEach
    void setUp() {
        User testUser = new User();
        testUser.setUsername("test_geh_user");
        testUser.setPassword(passwordEncoder.encode("123456"));
        testUser.setNickname("异常处理测试用户");
        testUser.setRole("GUEST");
        testUser.setStatus(1);
        testUser.setDeleted(0);
        userMapper.insert(testUser);

        // 用真实 id 签发 token
        validToken = jwtUtil.generateToken(testUser.getId(), testUser.getUsername(), testUser.getRole());
    }

    /** ① 业务异常 → 返回它对应的 code（这里 PARAM_ERROR=400） */
    @Test
    void businessException_shouldReturnItsCode() throws Exception {
        mockMvc.perform(get("/test/biz")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    /** ② 未知异常 → 统一返回 500 */
    @Test
    void unknownException_shouldReturn500() throws Exception {
        mockMvc.perform(get("/test/boom")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ERROR.getCode()));
    }

    /** ③ 参数校验失败（@NotBlank 空用户名）→ 返回 400 + 校验消息 */
    @Test
    void blankUsername_shouldReturnParamError() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    /** 注册测试控制器：/test/biz 抛业务异常，/test/boom 抛未知异常 */
    @TestConfiguration
    static class TestConfig {
        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {
        @GetMapping("/test/biz")
        public String biz() {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }

        @GetMapping("/test/boom")
        public String boom() {
            throw new RuntimeException("故意抛出的未知异常");
        }
    }
}
