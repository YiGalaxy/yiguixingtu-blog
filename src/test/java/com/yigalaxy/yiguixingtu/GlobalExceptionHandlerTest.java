package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试全局异常处理器（GlobalExceptionHandler）：
 *  业务异常、未知异常、参数校验失败 是否都被统一转成规范 Result
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;

    /** ① 业务异常 → 返回它对应的 code（这里 PARAM_ERROR=400） */
    @Test
    void businessException_shouldReturnItsCode() throws Exception {
        // 请求 /test/biz 需要登录，先造一个合法 token
        String token = jwtUtil.generateToken(1L, "tester", "GUEST");

        mockMvc.perform(get("/test/biz")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    /** ② 未知异常 → 统一返回 500 */
    @Test
    void unknownException_shouldReturn500() throws Exception {
        String token = jwtUtil.generateToken(1L, "tester", "GUEST");

        mockMvc.perform(get("/test/boom")
                        .header("Authorization", "Bearer " + token))
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