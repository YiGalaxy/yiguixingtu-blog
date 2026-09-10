package com.yigalaxy.yiguixingtu;

import com.jayway.jsonpath.JsonPath;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录功能集成测试：真正走 登录->拿token->带token访问/auth/me 完整链路
 */
class AuthLoginTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 每个测试前，插入一个测试用户（密码=123456，BCrypt加密） */
    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("tester");
        user.setPassword(passwordEncoder.encode("123456"));
        user.setNickname("测试用户");
        user.setRole("GUEST");
        user.setStatus(1);
        user.setDeleted(0);
        userMapper.insert(user);
    }

    /** ① 登录成功：code=200，token 非空，username=tester */
    @Test
    void loginSuccess_shouldReturnToken() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tester\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.username").value("tester"));
    }

    /** ② 密码错误：code=400（用户名或密码错误） */
    @Test
    void loginWrongPassword_shouldReturnBadCredentials() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tester\",\"password\":\"wrongpwd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /** ③ 带 token 访问 /auth/me：返回当前用户信息 */
    @Test
    void me_withToken_shouldReturnUserInfo() throws Exception {
        // 先登录，拿 token
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tester\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();

        // 用 json-path 从登录返回里取出 token
        String token = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.data.token");

        // 带 token 访问 /auth/me
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("tester"))
                .andExpect(jsonPath("$.data.role").value("GUEST"));
    }

    /** ④ 不带 token 访问 /auth/me：应返回 401 */
    @Test
    void me_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}