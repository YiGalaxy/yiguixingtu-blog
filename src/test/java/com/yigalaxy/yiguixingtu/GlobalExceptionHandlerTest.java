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

    /**
     * ④ 地址不存在 → HTTP 状态码【真的】是 404，body 的 code 也是 404
     *
     * 【为什么这条用例的断言方式和上面三条不一样】
     *   上面三条都是"业务错误"，按本项目的约定走 HTTP 200 + body.code。
     *   而"地址不存在"根本不是业务错误 —— 是【请求本身有问题】。
     *   这一类如果也报 200，后果是：
     *     · Nginx 日志、监控告警、负载均衡都只看状态码，全站错误率永远显示 0%
     *     · 调用方分不清"我地址拼错了"还是"后端崩了"
     *   所以这里【同时断言状态码和 body.code】，两者必须都是 404。
     *
     * 【为什么这条能安全地改，而业务错误不改】
     *   前端永远不会主动请求一个不存在的地址，正常流程走不到这里，
     *   所以对前端零影响；而业务错误一旦改状态码，
     *   前端所有接口的错误分支都要重新回归。这是算过账的取舍。
     */
    @Test
    void unknownEndpoint_shouldReturnReal404() throws Exception {
        mockMvc.perform(get("/no-such-endpoint-at-all")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.ENDPOINT_NOT_FOUND.getCode()));
    }

    /**
     * ⑤ HTTP 方法用错 → HTTP 405（而不是被兜底成 500）
     *
     * 测试控制器里 /test/biz 只声明了 GET，这里用 POST 去打它。
     * 不单独接住的话它会落到 @ExceptionHandler(Exception.class)，
     * 报成 500「服务器内部错误」—— 调用方明明是自己写错了方法，
     * 却看起来像后端挂了，很容易互相甩锅。
     */
    @Test
    void wrongHttpMethod_shouldReturn405() throws Exception {
        mockMvc.perform(post("/test/biz")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(ResultCode.METHOD_NOT_ALLOWED.getCode()));
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
