package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.about.dto.AboutVO;
import com.yigalaxy.yiguixingtu.about.entity.About;
import com.yigalaxy.yiguixingtu.about.mapper.AboutMapper;
import com.yigalaxy.yiguixingtu.about.service.AboutService;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 关于模块测试（AboutController / AdminAboutController / AboutServiceImpl）
 *
 * 【本类与另外三个内容模块测试最大的不同：数据只有一份】
 *   所以这里没有"按名字找那一条""排序""列表里看不到"这套断言，
 *   取而代之的是三条【单条记录特有】的不变量，每条都有一个用例钉住：
 *
 *  ① **永远只有一行**：反复保存之后 count(*) 仍然是 1（不会插出第二行）
 *  ② **那一行被删掉之后系统要能自己恢复**：GET 仍然返回 200 的"壳"
 *     （公开页面不该因为某条数据缺失变成错误页），保存能把这一行写回来
 *     —— 这是对"单条只改不建"的一处有意放宽，所以必须有用例守着
 *  ③ **没有新建接口**：POST /admin/about 是真 405（而不是"悄悄什么都不做"），
 *     说明"只有一份数据"这件事在接口形状上是真的（不是靠文档约定）
 *
 * 【其余的通用检查一个不少】校验边界（空 / 超长 / 非法 URL / 非法邮箱）、
 * 清空可选字段真的写成 NULL、权限 401/403、缓存命中与写操作失效。
 * 审计断言在 OperationLogTest 第⑱条（本类 @Transactional 跑完回滚，
 * AFTER_COMMIT 的审计永远等不到提交）。
 * =====================================================================
 */
class AboutTest extends AbstractIntegrationTest {

    /** 关于页缓存在 Redis 里的完整前缀（与 RedisConfig 的 computePrefixWith 规则一致） */
    private static final String ABOUT_CACHE_PREFIX = RedisConfig.CACHE_ABOUT + ":v1:";

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AboutService aboutService;

    @Autowired
    private AboutMapper aboutMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ContentCacheVersion contentCacheVersion;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String mark;
    private String adminToken;
    private String guestToken;

    @BeforeEach
    void setUp() {
        mark = "B" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        User admin = insertUser("test_admin_about", "ADMIN");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        // 真的插一个 GUEST：身份以数据库里的角色为准（给 ADMIN 签 GUEST token 依然是 ADMIN）
        User guest = insertUser("test_guest_about", "GUEST");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        clearAboutCache();
    }

    @AfterEach
    void tearDown() {
        clearAboutCache();
    }

    private void clearAboutCache() {
        Set<String> keys = redis.keys(ABOUT_CACHE_PREFIX + "*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    //  一、公开读
    // ================================================================

    @Test
    @DisplayName("① 游客不登录就能读关于页，且 data 是一个对象（不是数组）")
    void publicGet_withoutToken_shouldReturnSingleObject() throws Exception {
        mockMvc.perform(get("/about"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 【为什么要断言"不是数组"】数据只有一份，接口就应当返回对象 ——
                // 返回数组会让前端被迫写 data[0]，而"万一空数组"的崩溃只能靠运行时发现
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @DisplayName("② 迁移脚本已经把那一行插好了：库里恰好一行、id = 1、昵称非空")
    void migrationAlreadyInsertedTheSingleRow() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM about", Integer.class);
        assertEquals(1, count == null ? 0 : count, "关于表在迁移之后应当恰好有一行");

        Long id = jdbcTemplate.queryForObject("SELECT id FROM about", Long.class);
        assertEquals(About.SINGLE_ROW_ID, id, "那一行的 id 固定是 1");

        String nickname = jdbcTemplate.queryForObject("SELECT nickname FROM about", String.class);
        assertNotNull(nickname, "nickname 是 NOT NULL，迁移里给了初始值");
        assertTrue(nickname.length() > 0, "初始昵称不该是空串");
    }

    // ================================================================
    //  二、后台保存（正常路径 + 边界）
    // ================================================================

    @Test
    @DisplayName("③ 保存关于页 -> 200，库里逐字段更新（用原生 SQL 核对）")
    void updateAbout_shouldPersistToDatabase() throws Exception {
        String nickname = "昵称" + mark;

        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson(nickname, "/avatar.png", "自我介绍第一行\n\n第二行",
                                "me@example.com", "https://github.com/example", "wx_" + mark, "12345678")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 断言落到数据库：直查原生列才是"这一行到底存了什么"的权威答案
        assertEquals(nickname, rawValue("nickname", String.class));
        assertEquals("/avatar.png", rawValue("avatar", String.class));
        assertEquals("自我介绍第一行\n\n第二行", rawValue("bio", String.class),
                "自我介绍要原样存取（Markdown 原文，后端不做转换）");
        assertEquals("me@example.com", rawValue("email", String.class));
        assertEquals("https://github.com/example", rawValue("github", String.class));
        assertEquals("wx_" + mark, rawValue("wechat", String.class));
        assertEquals("12345678", rawValue("qq", String.class));

        // 而且不会插出第二行
        assertEquals(1, countRows(), "关于表永远只有一行");
    }

    @Test
    @DisplayName("④ 保存之后前台立刻看到新值（缓存被推进版本号作废）")
    void updateAbout_shouldRefreshPublicGet() throws Exception {
        String before = aboutService.get().getNickname();
        assertNotNull(before, "前置条件：先读一次，把缓存建起来");

        String nickname = "新昵称" + mark;
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson(nickname, null, "新的自我介绍", null, null, null, null)))
                .andExpect(jsonPath("$.code").value(200));

        AboutVO after = aboutService.get();
        assertEquals(nickname, after.getNickname(), "保存之后前台应当立刻是新昵称（缓存已失效）");
        assertEquals("新的自我介绍", after.getBio());
    }

    @Test
    @DisplayName("⑤ 昵称空白 / 超长（51）-> 400（校验与列长度一致）")
    void updateAbout_invalidNickname_shouldReturn400() throws Exception {
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("   ", null, null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("长".repeat(51), null, null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("昵称最长 50 字")));
    }

    @Test
    @DisplayName("⑥ 头像：站内路径与绝对地址合法，javascript: 被拒（它会被渲染成 <img src>）")
    void updateAbout_avatarBoundaries() throws Exception {
        // 站内相对路径（图放在前端 public 目录里）
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("头像站内" + mark, "/photo.jpg", null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("/photo.jpg", rawValue("avatar", String.class));

        // 上传接口返回的绝对地址
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("头像绝对" + mark, "https://cdn.example.com/a.png",
                                null, null, null, null, null)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("https://cdn.example.com/a.png", rawValue("avatar", String.class));

        // 脚本协议必须被拦下（这个值会被前台塞进 <img src>，等于一条 XSS）
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("脚本头像" + mark, "javascript:alert(1)",
                                null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑦ GitHub：必须是 http(s) 外链；裸域名被拒；留空则清成 NULL")
    void updateAbout_githubBoundaries() throws Exception {
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("github裸域名" + mark, null, null, null,
                                "github.com/example", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("http")));

        // 先填上
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("github先填" + mark, null, null, null,
                                "https://github.com/example", null, null)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("https://github.com/example", rawValue("github", String.class));

        // 再清空（空串 = 想从页面上拿掉它）
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("github清空" + mark, null, null, null, "", null, null)))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(rawValue("github", String.class), "清空 GitHub 必须真的写成 NULL");
    }

    @Test
    @DisplayName("⑧ 邮箱：格式不对被拒；空串按清空处理（不想公开邮箱就不填）")
    void updateAbout_emailBoundaries() throws Exception {
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("邮箱错误" + mark, null, null, "not-an-email", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("邮箱格式不正确")));

        // 合法的邮箱能存进去
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("邮箱正确" + mark, null, null, "hi@example.com", null, null, null)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("hi@example.com", rawValue("email", String.class));

        // 空串 = 清空（@Email 对空串放行，这正是我们要的：不想公开邮箱就清掉）
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("邮箱清空" + mark, null, null, "", null, null, null)))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(rawValue("email", String.class), "清空邮箱必须真的写成 NULL");
    }

    @Test
    @DisplayName("⑨ 自我介绍超长（5001）-> 400；微信 / QQ 超长 -> 400（纯文字字段只卡长度）")
    void updateAbout_textLengthBoundaries() throws Exception {
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("简介超长" + mark, null, "自".repeat(5001), null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("自我介绍最长 5000 字")));

        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("微信超长" + mark, null, null, null, null, "微".repeat(51), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("微信最长 50 字")));

        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("QQ超长" + mark, null, null, null, null, null, "1".repeat(31))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("QQ 最长 30 字")));

        // 微信 / QQ 是纯文字，不做格式校验：带下划线、字母、甚至一句话都合法
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("自由格式" + mark, null, null, null, null,
                                "wechat_id-2026", "QQ 邮箱同号")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("wechat_id-2026", rawValue("wechat", String.class));
        assertEquals("QQ 邮箱同号", rawValue("qq", String.class));
    }

    @Test
    @DisplayName("⑩ 一次把全部可选字段清空 -> 库里真的都是 NULL（守「用 LambdaUpdateWrapper」）")
    void updateAbout_clearAllOptionalFields_shouldActuallySetNull() throws Exception {
        // 先填满
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("填满" + mark, "/a.png", "有简介", "a@example.com",
                                "https://github.com/a", "wx_a", "10001")))
                .andExpect(jsonPath("$.code").value(200));

        // 全部清空（提交空串 —— 前端清空输入框就长这样）。
        // 用 updateById 的话这些 null 字段会被跳过，页面上那些内容永远删不掉，
        // 用户看到的是"清空之后一刷新又回来了"，而且没有任何报错
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("清空后" + mark, "", "", "", "", "", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(rawValue("avatar", String.class), "头像应当被清成 NULL");
        assertNull(rawValue("bio", String.class), "自我介绍应当被清成 NULL");
        assertNull(rawValue("email", String.class), "邮箱应当被清成 NULL");
        assertNull(rawValue("github", String.class), "GitHub 应当被清成 NULL");
        assertNull(rawValue("wechat", String.class), "微信应当被清成 NULL");
        assertNull(rawValue("qq", String.class), "QQ 应当被清成 NULL");
        assertEquals("清空后" + mark, rawValue("nickname", String.class), "昵称是必填，只更新不置空");
    }

    // ================================================================
    //  三、单条记录特有的不变量
    // ================================================================

    @Test
    @DisplayName("⑪ 反复保存 -> 永远只有一行（不会插出第二行）")
    void updateAbout_repeatedly_shouldKeepExactlyOneRow() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(put("/admin/about")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(aboutJson("第 " + i + " 次" + mark, null, null, null, null, null, null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals(1, countRows(), "第 " + i + " 次保存之后仍然只能有一行");
        }
    }

    @Test
    @DisplayName("⑫ 那一行被物理删掉之后：GET 仍然 200 返回空壳，保存能把它写回来")
    void missingRow_shouldSelfHealInsteadOfBreakingPublicPage() throws Exception {
        // 模拟"有人手工把那一行删了"（迁移脚本正常情况下会插好它）
        jdbcTemplate.update("DELETE FROM about");
        clearAboutCache();
        assertEquals(0, countRows(), "前置条件：那一行确实没了");

        // 【第一半：公开页面不能因此变成错误页】返回 200 + 一个内容为空的壳，
        // 而不是 404 / 500 —— 一个"只是没内容"的页面不该表现得像"这个站坏了"
        mockMvc.perform(get("/about"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                // 昵称给了占位值（它在前台是必显示项，空字符串会渲染成一片空白，
                // 而"站长"至少让人看出这里本来有内容）
                .andExpect(jsonPath("$.data.nickname").value("站长"));

        // 其余字段是空的（这里用 Service 断言而不是 jsonPath：
        // "字段为 null"在 JSON 里既可能表现为 "bio":null、也可能被序列化配置省略掉，
        // 断言它的有无会把测试绑在 Jackson 的配置上 —— 那不是这条用例要证明的事）
        AboutVO shell = aboutService.get();
        assertNull(shell.getBio(), "那一行不在时，自我介绍应当是空的");
        assertNull(shell.getAvatar(), "头像同理");

        // 【第二半：后台保存要把这一行救回来】，而不是静静地什么都不做
        // （返回 200 说"保存成功"、库里却没有任何变化，是最糟的一种失败）
        String nickname = "救回来" + mark;
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson(nickname, "/recovered.png", "自我介绍回来了",
                                null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, countRows(), "自愈之后应当恰好一行（不是两行）");
        assertEquals(About.SINGLE_ROW_ID, jdbcTemplate.queryForObject("SELECT id FROM about", Long.class),
                "写回来的那一行 id 必须是 1");
        assertEquals(nickname, rawValue("nickname", String.class));
        assertEquals("/recovered.png", rawValue("avatar", String.class));
        assertEquals(nickname, aboutService.get().getNickname(), "前台随后就能读到它");
    }

    @Test
    @DisplayName("⑬ 关于页没有新建接口：POST /admin/about 是真 405（不是静默成功）")
    void postAbout_shouldReturn405() throws Exception {
        // 【为什么专门测这个】"只有一份数据"这件事在接口形状上是真的：
        // 只有 PUT，没有 POST。如果不测，将来有人顺手加一个 POST 新接口，
        // 库里就可能出现第二行，而所有"只有一份"的假设都会悄悄失效
        mockMvc.perform(post("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("想新建" + mark, null, null, null, null, null, null)))
                // 405 是【真状态码】：地址对、方法用错属于"请求本身有问题"，
                // 和业务错误（HTTP 200 + body.code）分区处理，见 README「统一返回与错误处理」
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));

        assertEquals(1, countRows(), "POST 被拒之后库里仍然只有一行");
    }

    // ================================================================
    //  四、权限
    // ================================================================

    @Test
    @DisplayName("⑭ 权限：保存接口对游客 403、对无 token 401；读接口对谁都开放")
    void adminAboutApi_shouldRequireAdmin() throws Exception {
        String body = aboutJson("游客偷偷改的", null, null, null, null, null, null);

        mockMvc.perform(put("/admin/about")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        // 读接口是公开的（游客也能拿）—— 这条同时守住"别把 /about 顺手改成要登录"
        mockMvc.perform(get("/about")).andExpect(status().isOk());
        mockMvc.perform(get("/about").header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());
    }

    // ================================================================
    //  五、缓存
    // ================================================================

    @Test
    @DisplayName("⑮ 读接口走缓存：绕过 Service 直接改库 -> 读到的还是缓存里那份")
    void getAbout_shouldServeFromCache() {
        aboutService.get();
        assertNotNull(awaitAboutKeyWithVersion(contentCacheVersion.current(), 2000),
                "第一次读之后 Redis 里应当出现 about:detail 的缓存 key");

        String signed = "绕过Service改的" + mark;
        // 绕过 Service 直接改库：Mapper 上没有任何缓存注解，所以缓存不会失效
        jdbcTemplate.update("UPDATE about SET nickname = ? WHERE id = 1", signed);

        // 缓存命中 → 读到的还是改之前那份（如果是未命中，读出来的就会是 signed）
        assertNotEquals(signed, aboutService.get().getNickname(),
                "这次应当直接吃缓存，读不到绕过 Service 改的值");
    }

    @Test
    @DisplayName("⑯ 保存推进版本号；缓存 key 带当前版本号且有 TTL")
    void updateAbout_shouldBumpVersionAndCacheKeyShouldCarryVersion() throws Exception {
        aboutService.get();
        long versionBefore = Long.parseLong(contentCacheVersion.current());

        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(aboutJson("推进版本" + mark, null, null, null, null, null, null)))
                .andExpect(jsonPath("$.code").value(200));

        assertTrue(Long.parseLong(contentCacheVersion.current()) > versionBefore,
                "保存之后版本号应当推进（否则前台关于页要等 TTL 才更新）");

        // 版本号变了 → 刚读的缓存 key 已经拼不出来 → 重新读会写入一条【新版本】的 key。
        // ⚠️ 这里不能断言"有且只有一条 key"：老版本那条还在 Redis 里（等 TTL 自然过期）；
        //    也不能只等"出现任意一条 key"—— 老 key 早就在了，会立刻满足条件、
        //    而新 key 可能还没写进去（Redis 写入是异步发出的）。
        //    所以要等的是【带当前版本号】的那一条
        aboutService.get();
        String key = awaitAboutKeyWithVersion(contentCacheVersion.current(), 2000);
        assertNotNull(key, "应当出现一条带当前版本号的缓存 key");

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "缓存必须有 TTL 兜底，实际剩余秒数=" + ttl);
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /**
     * 拼保存关于页的请求体：null 表示"这个字段不出现在 JSON 里"。
     * 注意空串与 null 是两种输入：空串 = 想清空这一栏，null（不出现）= 本次没提交它。
     * 对关于页来说两者都会被归一化成 NULL（整份表单覆盖式提交），但走的校验路径不同。
     */
    private String aboutJson(String nickname, String avatar, String bio, String email,
                             String github, String wechat, String qq) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"nickname\":\"").append(nickname == null ? "" : nickname).append("\"");
        if (avatar != null) {
            sb.append(",\"avatar\":\"").append(avatar).append("\"");
        }
        if (bio != null) {
            // bio 里可能有换行，JSON 里必须转义成 \n
            sb.append(",\"bio\":\"").append(bio.replace("\n", "\\n")).append("\"");
        }
        if (email != null) {
            sb.append(",\"email\":\"").append(email).append("\"");
        }
        if (github != null) {
            sb.append(",\"github\":\"").append(github).append("\"");
        }
        if (wechat != null) {
            sb.append(",\"wechat\":\"").append(wechat).append("\"");
        }
        if (qq != null) {
            sb.append(",\"qq\":\"").append(qq).append("\"");
        }
        return sb.append("}").toString();
    }

    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM about", Integer.class);
        return count == null ? 0 : count;
    }

    /** 直查那一行的某一列（关于表只有一行，所以不需要 id 条件） */
    private <T> T rawValue(String column, Class<T> type) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM about", type);
    }

    private User insertUser(String usernamePrefix, String role) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("关于测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    /**
     * 轮询等待"带指定版本号的那条缓存 key"出现。
     *
     * 【为什么不是"等出现任意一条 key"】老版本的 key 还在 Redis 里（等 TTL 自然过期），
     * 所以"有 key 了"这个条件会立刻成立，而我们要等的通常是【刚 bump 之后新写的那条】——
     * 两者差一个版本号，用"数量"当条件就会误判。
     *
     * 【为什么要轮询】Spring Data Redis 的写入是异步发出的（连接池上另一条连接），
     * 方法返回时 KEYS 可能还看不到它 —— 直接断言会偶发变红（这个坑在 ArticleCacheTest 里踩过）。
     *
     * @return 带该版本号的 key；超时仍未出现时返回 null
     */
    private String awaitAboutKeyWithVersion(String version, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Set<String> keys = redis.keys(ABOUT_CACHE_PREFIX + "*");
            if (keys != null) {
                for (String key : keys) {
                    if (key.contains(version)) {
                        return key;
                    }
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }
}
