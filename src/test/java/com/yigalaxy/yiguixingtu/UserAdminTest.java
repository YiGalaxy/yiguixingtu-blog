package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.cache.UserAuthCache;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 用户管理接口测试（UserController）
 *
 * 【最关键的一点：@Transactional 让测试自动回滚】
 *   基类 AbstractIntegrationTest 上加了 @Transactional，Spring 会把整个
 *   测试方法包在一个事务里，测试跑完（不管成功还是失败）自动 ROLLBACK。
 *   所以 @BeforeEach 里插入的测试用户、测试过程中改的状态/角色，
 *   【都不会真正留在数据库里】—— 这正是你要的"用完就删"。
 *
 * 【前置条件】
 *   只需本机有 Docker。MySQL 与 Redis 由 Testcontainers 在测试启动时
 *   自动拉起（见 AbstractIntegrationTest），不再需要先 docker compose up -d。
 *
 * 【注意】不要在类或方法上加 @Commit、@Rollback(false)，
 *        那会真的写进数据库，破坏"自动清理"。
 * =====================================================================
 */
class UserAdminTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserAuthCache userAuthCache;

    /** 测试用的管理员、游客，以及各自的 token */
    private User admin;
    private User guest;
    private String adminToken;
    private String guestToken;

    /**
     * 每个测试方法跑之前都会执行一次：
     * 插一个 ADMIN、一个 GUEST，并生成对应 token。
     * 因为整体事务会回滚，这些数据不会残留。
     */
    @BeforeEach
    void setUp() {
        admin = insertUser("test_admin_um", "ADMIN");
        guest = insertUser("test_guest_um", "GUEST");

        // 直接用 JwtUtil 造 token，比走登录接口更快，
        // 而且能精确控制 token 里携带的 userId 和 role
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");
    }

    /** 工具方法：插入一个用户并返回（insert 后 id 会被 MyBatis-Plus 回填到对象里） */
    private User insertUser(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    // ================================================================
    // 一、权限验证：能否访问用户管理接口
    // ================================================================

    @Test
    @DisplayName("① 管理员访问分页列表 -> 200，返回分页结构")
    void adminPage_shouldReturn200() throws Exception {
        mockMvc.perform(get("/user/page")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").isNumber());
    }

    @Test
    @DisplayName("② 游客访问用户管理 -> 403 无权限")
    void guest_shouldReturn403() throws Exception {
        mockMvc.perform(get("/user/page")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("③ 不带 token 访问 -> 401 未登录")
    void noToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/user/page"))
                .andExpect(status().isUnauthorized());
    }

    // ================================================================
    // 二、列表查询：筛选 + 安全检查
    // ================================================================

    @Test
    @DisplayName("④ 关键词筛选生效")
    void page_withKeyword_shouldFilter() throws Exception {
        mockMvc.perform(get("/user/page")
                        .param("keyword", "test_admin_um")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].username").value("test_admin_um"));
    }

    @Test
    @DisplayName("⑤ 角色筛选生效（只查 GUEST）")
    void page_withRoleFilter_shouldFilter() throws Exception {
        mockMvc.perform(get("/user/page")
                        .param("role", "GUEST")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 返回的每一条 role 都应该是 GUEST（只校验第一条即可）
                .andExpect(jsonPath("$.data.records[0].role").value("GUEST"));
    }

    @Test
    @DisplayName("⑥ 返回结果绝不能包含 password 字段")
    void page_shouldNotExposePassword() throws Exception {
        mockMvc.perform(get("/user/page")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].password").doesNotExist());
    }

    // ================================================================
    // 分页参数兜底：size 传多大都不能把整张表捞出来
    //
    // 【这三条用例在守什么】
    //   分页接口的 size 是【客户端可以随便改】的参数：前端界面上写的是
    //   一页 10 条，但只要有人把 URL 改成 ?size=999999，后端就得照做 ——
    //   一次查询会把整张 user 表捞进内存、再序列化成 JSON 发出去。
    //   一个请求就能把数据库连接、内存、带宽一起吃掉，而且不需要任何额外权限。
    //
    // 【被守的这道防线在哪（先查清楚再写用例，别凭印象）】
    //   用户分页的上限是 UserServiceImpl.pageUsers() 里这一段：
    //       long pageSize = (size == null || size < 1) ? 10L : size;
    //       if (pageSize > 100) pageSize = 100;
    //   也就是说【上限逻辑一直是有的】，只是以前没有任何用例盯着它 ——
    //   谁哪天顺手删掉那个 if，不会有任何测试变红。
    //   这三条用例就是把这段已经正确的行为固定下来（回归测试）。
    //   ⚠️ 我一开始以为这里漏了夹取，是 grep 时用的关键字是 Math.min，
    //      而它其实写成了 if 语句，所以没搜到。教训：断言"某处有 bug"之前
    //      一定要把源码整段读完，并把预期失败先复现出来。
    //
    // 【另外还有一道全局底线，见 MybatisPlusConfig.MAX_PAGE_SIZE】
    //   它不改变这里的预期结果（100 恰好等于底线），
    //   作用是在所有分页查询的必经之路上兜一道，防止将来新增接口忘记夹取。
    //   那一层无法通过 HTTP 接口触发（现有接口都自己夹过了），
    //   所以单独放在 PaginationLimitTest 里从 Mapper 层直接验证。
    //
    // 【用到的东西】
    //   · Spring MVC 的参数绑定：`?size=999999` 由 Spring 按字段名塞进 UserQuery
    //   · MyBatis-Plus 的 PaginationInnerInterceptor：把 page 对象变成 LIMIT 查询
    //   · MockMvc + jsonPath：断言【实际生效的 size】，
    //     而不是只断言 HTTP 200（只断言 200 的话，一次捞出 99 万条同样返回 200）
    //
    // 三条分别盯住三个方向：超大要夹住、非法值要有兜底、正好等于上限时不能误夹。
    // ================================================================

    @Test
    @DisplayName("㉖ size 传超大值 -> 被上限夹住，不会真去捞整张表")
    void page_sizeTooLarge_shouldBeClamped() throws Exception {
        mockMvc.perform(get("/user/page")
                        // 这个数字远远超过表里的数据量：如果没夹住，
                        // SQL 就会变成 LIMIT 999999，接口会把全部用户都返回出去
                        .param("size", "999999")
                        .header("Authorization", "Bearer " + adminToken))
                // 外层 HTTP 状态：接口本身是正常处理的，不该因为这个参数报错
                // （夹取是静默的，不是抛异常）
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 关键断言：响应里回显的 size 必须是夹取后的 100。
                // ⚠️ 这个 100 来自 UserServiceImpl 里的 if (pageSize > 100) 判断，
                //    不是分页插件夹的 —— 插件只改写 SQL 的 LIMIT，
                //    不会改动 Page 对象上的 size 字段（详见 MybatisPlusConfig 的注释）
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    @DisplayName("㉗ size 传 0 / 负数 -> 回落成默认 10 条，不报错也不返回空")
    void page_invalidSize_shouldFallBackToDefault() throws Exception {
        // 这两种输入是"没意义"而不是"恶意"：0 条和负条数的分页没有定义，
        // 后端应该给出合理默认值，而不是抛异常或返回空列表
        // （返回空的后果是前端显示"没有数据"，用户以为系统坏了）
        for (String bad : new String[]{"0", "-5"}) {
            mockMvc.perform(get("/user/page")
                            .param("size", bad)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    // 默认值是 10：UserServiceImpl 里 `size < 1` 时用 10L 兜底
                    .andExpect(jsonPath("$.data.size").value(10));
        }
    }

    @Test
    @DisplayName("㉘ size 正好等于上限 -> 不该被夹小（别把上限做成 99）")
    void page_sizeAtLimit_shouldNotBeClamped() throws Exception {
        // 这条是防止夹取写成 `>=` 之类的差一错误：上限是 100，那 100 就必须能用。
        // 否则以后有人要一页取 100 条做导出，会发现怎么调都只拿 99 条，还很难查
        mockMvc.perform(get("/user/page")
                        .param("size", "100")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    // ================================================================
    // 三、启用 / 禁用
    // ================================================================

    @Test
    @DisplayName("⑦ 禁用普通用户 -> 200，且数据库状态真的变成 0")
    void disableUser_shouldUpdateStatusInDb() throws Exception {
        mockMvc.perform(put("/user/{id}/status", guest.getId())
                        .param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 直接查库确认（同一事务内，能看到本次修改）
        User after = userMapper.selectById(guest.getId());
        assertEquals(0, after.getStatus(), "禁用后 status 应该是 0");
    }

    @Test
    @DisplayName("⑧ 再次启用 -> 状态回到 1")
    void enableUser_shouldUpdateStatusInDb() throws Exception {
        // 先禁用
        mockMvc.perform(put("/user/{id}/status", guest.getId())
                        .param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 再启用
        mockMvc.perform(put("/user/{id}/status", guest.getId())
                        .param("status", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        User after = userMapper.selectById(guest.getId());
        assertEquals(1, after.getStatus(), "启用后 status 应该是 1");
    }

    @Test
    @DisplayName("⑨ 禁用不存在的用户 -> code 404 用户不存在")
    void disableNotExistUser_shouldReturn404() throws Exception {
        mockMvc.perform(put("/user/{id}/status", 999999999L)
                        .param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.USER_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("⑩ 非法状态值 -> code 400 参数校验失败")
    void invalidStatus_shouldReturn400() throws Exception {
        mockMvc.perform(put("/user/{id}/status", guest.getId())
                        .param("status", "9")     // 只允许 0 或 1
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("⑪ 管理员禁用自己 -> 400 且提示不能禁用自己")
    void disableSelf_shouldBeRejected() throws Exception {
        mockMvc.perform(put("/user/{id}/status", admin.getId())
                        .param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("不能禁用自己的账号"));
    }

    // ================================================================
    // 四、修改角色
    // ================================================================

    @Test
    @DisplayName("⑫ 把游客提升为管理员 -> 200，库里角色变成 ADMIN")
    void updateRole_shouldUpdateInDb() throws Exception {
        mockMvc.perform(put("/user/{id}/role", guest.getId())
                        .param("role", "ADMIN")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        User after = userMapper.selectById(guest.getId());
        assertEquals("ADMIN", after.getRole(), "角色应该被改成 ADMIN");
    }

    @Test
    @DisplayName("⑬ 非法角色值 -> code 400")
    void invalidRole_shouldReturn400() throws Exception {
        mockMvc.perform(put("/user/{id}/role", guest.getId())
                        .param("role", "SUPERMAN")     // 只允许 ADMIN / GUEST
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("⑭ 管理员修改自己的角色 -> 400 被拒绝")
    void updateOwnRole_shouldBeRejected() throws Exception {
        mockMvc.perform(put("/user/{id}/role", admin.getId())
                        .param("role", "GUEST")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("不能修改自己的角色"));
    }

    // ================================================================
    // 五、端到端：禁用后真的登不进来
    // ================================================================

    @Test
    @DisplayName("⑮ 用户被禁用后，无法再登录（端到端验证）")
    void disabledUser_cannotLogin() throws Exception {
        // 1. 管理员把游客禁用
        mockMvc.perform(put("/user/{id}/status", guest.getId())
                        .param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 2. 该用户拿着正确密码去登录 -> 应该被拒（LoginUser.isEnabled() 读的就是 status）
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test_guest_um\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ACCOUNT_DISABLED.getCode()));
    }

    // ================================================================
    // 六、token 失效验证（本次修复的核心）
    // ================================================================

    @Test
    @DisplayName("⑯ 用户被禁用后，他手里的旧 token 立即失效 -> 401")
    void disabledUser_oldToken_shouldBeRejected() throws Exception {
        // 【预热】先让 guest 正常访问一次，把它写进缓存
        // （关键！不预热的话，缓存本来就是空的，测不出 evict 有没有生效）
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());

        // 管理员禁用 guest
        mockMvc.perform(put("/user/{id}/status", guest.getId())
                        .param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 若 evict 漏写，这里会命中"status=1"的脏缓存 → 返回 200，测试失败
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("⑰ 用户被删除后，他手里的旧 token 立即失效 -> 401")
    void deletedUser_oldToken_shouldBeRejected() throws Exception {
        // 【预热】
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());

        // 管理员删除 guest
        mockMvc.perform(delete("/user/{id}", guest.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 若 evict 漏写，会命中旧缓存（用户"还在"）→ 200，测试失败
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("⑱ 管理员被降级后，旧 token 立即失去管理权限 -> 403")
    void demotedAdmin_oldToken_shouldLoseAdminRights() throws Exception {
        // 造第二个管理员
        User admin2 = insertUser("test_admin2_um", "ADMIN");
        String admin2Token = jwtUtil.generateToken(admin2.getId(), admin2.getUsername(), "ADMIN");

        // 【预热】admin2 先成功访问一次管理接口，把"ADMIN"写进缓存
        mockMvc.perform(get("/user/page")
                        .header("Authorization", "Bearer " + admin2Token))
                .andExpect(status().isOk());

        // 把 admin2 降级为游客
        mockMvc.perform(put("/user/{id}/role", admin2.getId())
                        .param("role", "GUEST")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 若 evict 漏写，会命中缓存里的旧角色 ADMIN → 200，测试失败
        mockMvc.perform(get("/user/page")
                        .header("Authorization", "Bearer " + admin2Token))
                .andExpect(status().isForbidden());
    }

    @AfterEach
    void tearDown() {
        // @Transactional 只能回滚数据库，回滚不了 Redis。
        // 这里手动清掉本用例产生的缓存 key，避免污染后续测试、累积垃圾。
        if (admin != null) userAuthCache.evict(admin.getId());
        if (guest != null) userAuthCache.evict(guest.getId());
    }
}
