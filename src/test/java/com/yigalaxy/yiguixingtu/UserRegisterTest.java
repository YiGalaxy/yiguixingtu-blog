// auth/UserRegisterTest.java
package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 注册接口的用例
 *
 * 【这组用例的重点是「邮箱是不是真的必填了」】
 *   `/auth/register` 是本项目【唯一】不需要登录就能写库的公开接口。
 *   邮箱这个字段加在这里的意义只有一个：**保证从此之后每个新账号都有邮箱**。
 *   而它失效的方式是静默的 —— 校验注解被删掉、被挪到别的字段上、
 *   或者 Service 里忘了 setEmail，表现都是"注册照常成功，只是邮箱是空的"，
 *   没有任何报错。所以三件事都要有用例钉着：
 *     ① 空邮箱 / 格式不对 → 拒绝（不是存个空串进去）
 *     ② 格式正确 → **真的落库了**（只看接口返回 200 证明不了这一点）
 *     ③ 老账号没有邮箱时接口不能崩（这一列可空，见 V18）
 * =====================================================================
 */
class UserRegisterTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    /** 拼一个注册请求体（转义交给调用方：这里的值都是 ASCII，不需要额外处理） */
    private static String body(String username, String password, String nickname, String email) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password
                + "\",\"nickname\":\"" + nickname + "\",\"email\":\"" + email + "\"}";
    }

    @Test
    @DisplayName("① 注册成功：返回 200，并且邮箱真的落库了")
    void registerSuccess_shouldReturn200() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("reg1", "123456", "用户", "reg1@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 【为什么还要查一次库】接口返回 200 只说明"没抛异常"。
        //   要是 Service 里漏了 setEmail，这条断言才是唯一能发现问题的地方 ——
        //   而它一旦漏掉，注册出来的账号就是一个没有邮箱的账号，
        //   与加这个字段之前的账号看起来一模一样，没人会发现。
        User saved = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, "reg1"));
        assertNotNull(saved, "注册成功后应当能查到这条用户");
        assertEquals("reg1@example.com", saved.getEmail(), "邮箱必须真的写进库里");
        assertEquals("GUEST", saved.getRole(), "自助注册的账号一律是普通用户，不能是管理员");
    }

    @Test
    @DisplayName("② 用户名重复：返回「账号已存在」")
    void registerDuplicate_shouldReturnUsernameExists() throws Exception {
        String request = body("reg2", "123456", "用户", "reg2@example.com");
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.USERNAME_EXISTS.getCode()));
    }

    @Test
    @DisplayName("③ 用户名为空：参数校验拦下")
    void registerBlankUsername_shouldReturnParamError() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "123456", "用户", "someone@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    // =================================================================
    //  邮箱（本组用例的重点，见类注释）
    // =================================================================

    @Test
    @DisplayName("④ ★ 邮箱为空：拒绝注册（不能存在一个没有邮箱的新账号）")
    void registerBlankEmail_shouldReturnParamError() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("reg_no_email", "123456", "用户", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));

        // 【这条断言才是重点】接口返回 400 是意料之中，但要确认它**真的没写库** ——
        //   校验注解写在 DTO 上，万一将来有人把它挪到 Controller 里手写检查、
        //   又写错了顺序，就可能出现"报了错但账号已经建出来"这种最难查的状态
        assertEquals(0, countUser("reg_no_email"), "被拦下的注册不该留下任何记录");
    }

    @Test
    @DisplayName("⑤ ★ 邮箱缺字段（整个字段都不传）：同样拒绝")
    void registerMissingEmailField_shouldReturnParamError() throws Exception {
        // 【为什么这条和 ④ 要分开】JSON 里"字段不存在"与"字段是空串"走的是两条路：
        //   @NotBlank 对 null 和 "" 都拦得住，但"字段没传"时 Jackson 给的是 null，
        //   而将来若有人把注解换成 @Email（它对 null 放行），
        //   旧前端（不带 email 字段）就能静默地注册出一个没有邮箱的账号。
        //   这条用例把那条路堵死。
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reg_absent\",\"password\":\"123456\",\"nickname\":\"用户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));

        assertEquals(0, countUser("reg_absent"), "缺字段的请求不该留下任何记录");
    }

    @Test
    @DisplayName("⑥ ★ 邮箱格式不对：拒绝注册")
    void registerInvalidEmail_shouldReturnParamError() throws Exception {
        String[] invalid = {
                "notanemail",             // 没有 @
                "has space@example.com",  // 中间有空格
                "no-domain@",             // @ 后面什么都没有
                "@no-local.com",          // @ 前面什么都没有
                "trailing@example.",      // 顶级域是空的
                "a@@b.com",               // 两个 @
                // ⚠️ 下面这两个是 @Email **单独拦不住**的（实测 Hibernate Validator 9.1
                //    把 a@b 和 plain@localhost 都判为合法）。它们由 RegisterRequest 上
                //    那条 @Pattern 拦下，用途是"域名必须有点"——
                //    没有点的域名对「站长能发信联系到用户」这个目的毫无用处。
                //    删掉 @Pattern 时这两条会立刻变红。
                "a@b",
                "plain@localhost",
        };
        for (int i = 0; i < invalid.length; i++) {
            String email = invalid[i];
            String username = "reg_bad_" + i;
            mockMvc.perform(post("/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(username, "123456", "用户", email)))
                    .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
            assertEquals(0, countUser(username), "格式不合法时不该落库：" + email);
        }
    }

    @Test
    @DisplayName("⑥-b 合法邮箱的各种形态都要放行（别把 + 号、子域名误伤了）")
    void registerValidEmailShapes_shouldSucceed() throws Exception {
        // 【为什么专门有一条】收紧校验最容易犯的错是「拦住了畸形值，也顺手拦住了真人」。
        //   带 + 号的地址、多级域名都是再普通不过的写法。
        String[][] valid = {
                {"reg_ok_1", "a+b@sub.example.cn"},
                {"reg_ok_2", "user@example.co.uk"},
                {"reg_ok_3", "someone@qq.com"},
        };
        for (String[] one : valid) {
            String username = one[0];
            String email = one[1];
            mockMvc.perform(post("/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(username, "123456", "用户", email)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals(1, countUser(username), "合法邮箱应当注册成功：" + email);
        }
    }

    @Test
    @DisplayName("⑦ 邮箱超长：拒绝（数据库列是 varchar(100)，超长会在 INSERT 时才炸成 500）")
    void registerTooLongEmail_shouldReturnParamError() throws Exception {
        String email = "a".repeat(95) + "@example.com";   // 107 字符
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("reg_long_email", "123456", "用户", email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    /** 按用户名数一下有几条记录（用于确认"被拦下的注册真的没写库"） */
    private long countUser(String username) {
        return userMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username));
    }
}
