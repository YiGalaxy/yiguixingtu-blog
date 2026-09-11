package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.setting.dto.SettingVO;
import com.yigalaxy.yiguixingtu.setting.entity.SiteSetting;
import com.yigalaxy.yiguixingtu.setting.service.SettingService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 站点设置模块测试（SettingController / AdminSettingController / SettingServiceImpl）
 *
 * 【它与 AboutTest 的相同之处：数据只有一份】
 *   所以同样有那三条"单条记录特有"的不变量（各自有用例钉住）：
 *     · 永远只有一行（反复保存不会插出第二行）
 *     · 那一行被物理删掉之后，公开读仍然 200、保存能把这一行自愈回来
 *     · 没有新建接口（POST /admin/setting 是真 405）
 *
 * 【它与 AboutTest 的关键不同：字段是【互相独立】的开关】
 *   关于页是一个"页面"——所有字段一起读、一起用，所以那边只要测"整份存进去了"。
 *   而这里六个字段各自驱动界面上很不一样的一处：
 *     站点名 → 页眉页脚与标题、公告 → 首页那一条、评论开关 → 文章页的评论框、
 *     备案号与版权 → 页脚、每页条数 → 首页列表请求的 size。
 *   后果是【任何一个字段悄悄失效，其它字段都还是好的】——
 *   页面看起来一切正常，只有那一处不动。所以这里对每个字段都单独有用例：
 *   能改、能读到、能清空（能清空是重点：公告会下掉、备案号会换）。
 *
 * 【两条格外重要的边界，各有一条用例】
 *   · ⑧ 每页条数：上限必须跟着文章接口的分页上限走（ArticleQuery.MAX_PAGE_SIZE）。
 *     这个值一旦允许超过接口上限，站长就会拿到一个"设置成功但不生效"的开关，
 *     而且没有任何报错 —— 属于最难查的一类问题
 *   · ⑨ 评论开关：实体里是 0/1、接口上是布尔，两个方向的转换都要有断言。
 *     只测一个方向的话，反着转换（1 变 false）也能全绿
 *
 * 缓存那几条与 AboutTest 同一套写法（走缓存 / 推进版本号 / key 带版本号且有 TTL），
 * 因为用的就是同一套机制。审计断言在 OperationLogTest 第⑲条
 * （本类 @Transactional 跑完回滚，AFTER_COMMIT 的审计永远等不到提交）。
 * =====================================================================
 */
class SiteSettingTest extends AbstractIntegrationTest {

    /** 站点设置缓存在 Redis 里的完整前缀（与 RedisConfig 的 computePrefixWith 规则一致） */
    private static final String SETTING_CACHE_PREFIX = RedisConfig.CACHE_SITE_SETTING + ":v1:";

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SettingService settingService;

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
        mark = "S" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        User admin = insertUser("test_admin_setting", "ADMIN");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        // 真的插一个 GUEST：身份以数据库里的角色为准（给 ADMIN 签 GUEST token 依然是 ADMIN）
        User guest = insertUser("test_guest_setting", "GUEST");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        clearSettingCache();
    }

    @AfterEach
    void tearDown() {
        clearSettingCache();
    }

    private void clearSettingCache() {
        Set<String> keys = redis.keys(SETTING_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    //  一、公开读
    // ================================================================

    @Test
    @DisplayName("① 游客不登录就能读站点设置，且 data 是一个对象（不是数组）")
    void publicGet_withoutToken_shouldReturnSingleObject() throws Exception {
        // 【为什么这条必须匿名可用】页脚里的备案号是合规要求，
        // 不能让未登录访客看不到；而前端每一页（含 404 页）都要读这一份数据
        mockMvc.perform(get("/setting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.data.siteName").isNotEmpty());
    }

    @Test
    @DisplayName("② 迁移已经把那一行插好了：库里恰好一行、id = 1；种子值读【迁移脚本原文】核对")
    void migrationAlreadyInsertedTheSingleRow() {
        // ---- 库里：只断言【结构性的不变量】（这些与谁先跑无关）----
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM site_setting", Integer.class);
        assertEquals(1, count == null ? 0 : count, "站点设置表在迁移之后应当恰好有一行");

        Long id = jdbcTemplate.queryForObject("SELECT id FROM site_setting", Long.class);
        assertEquals(SiteSetting.SINGLE_ROW_ID, id, "那一行的 id 固定是 1");

        String siteName = rawValue("site_name", String.class);
        assertNotNull(siteName, "站点名是 NOT NULL，迁移里给了初始值");
        assertFalse(siteName.isBlank(), "站点名不该是空串");
        int pageSize = rawValue("page_size", Integer.class);
        assertTrue(pageSize >= 1 && pageSize <= (int) ArticleQuery.MAX_PAGE_SIZE,
                "每页条数必须落在 1~" + ArticleQuery.MAX_PAGE_SIZE + " 之间，实际=" + pageSize);

        // ---- 种子值本身：读【迁移脚本原文】核对，而不是查库 ----
        //
        // ⚠️ 为什么不能查库断言（第一版就是那么写的，实测变红，记下来）
        //   ① site_setting 是"全表只有一行"的**共享状态**
        //   ② 所有测试类共用同一个 ApplicationContext 与同一个数据库
        //      （见 AbstractIntegrationTest 的注释）
        //   ③ 而 OperationLogTest ⑳（站点设置的审计用例）**必须真的提交事务**——
        //      审计是靠 @TransactionalEventListener(AFTER_COMMIT) 落库的，
        //      跑在事务里永远等不到提交。所以那个类不能加 @Transactional，
        //      它写进去的值会留下来
        //   ⇒ 于是"库里此刻的值"取决于测试执行顺序：实测被前一个类写成了
        //     "<mark>-审计站点名"，这条断言就红了。那是一个【必偶发】的断言。
        //   种子值是一件"迁移脚本里写了什么"的事实，就该去读那份脚本 ——
        //   与之并排的 ProfileProdConfigTest 也是同一个做法。
        String migration = readClasspathFile("db/migration/V12__create_site_setting_table.sql");

        assertTrue(migration.contains("VALUES (1, '亿轨星途', 1, 12)"),
                "迁移脚本应当把那一行插成（站点名 亿轨星途 / 评论开 / 每页 12）——"
                        + "这三个值合起来才保证「上线这个功能不改变站点外观」");
        // ⚠️ 12 不是 ArticleQuery.DEFAULT_PAGE_SIZE（10）：12 是前端首页此刻真正在用的
        //    每页条数（3 列瀑布流 4 行），10 是"接口没收到 size 时用什么"。用错会让首页
        //    在上线那一刻从 12 篇变成 10 篇 —— 见 V12 脚本里那段说明

        // INSERT 里【只】给这三列值：另外三列刻意不出现，它们保持 NULL = 前台不渲染
        // （公告条 / 备案号 / 版权行在上线前本来就没有对应元素），外观才与现在一致
        int insertAt = migration.indexOf("INSERT INTO `site_setting`");
        assertTrue(insertAt > 0, "迁移脚本里应当有一条 INSERT（把那一行插进去）");
        String insert = migration.substring(insertAt);
        for (String column : new String[]{"site_name", "comment_enabled", "page_size"}) {
            assertTrue(insert.contains(column), "INSERT 应当给 " + column + " 种子值");
        }
        for (String column : new String[]{"announcement", "icp_number", "copyright"}) {
            assertFalse(insert.contains(column),
                    "INSERT 不该给 " + column + " 值：留 NULL 才是「前台不渲染」，"
                            + "外观才与上线前一致");
        }
    }

    // ================================================================
    //  二、后台保存：逐字段（每个字段一条，因为它们互相独立）
    // ================================================================

    @Test
    @DisplayName("③ 保存 -> 200，库里逐字段更新（用原生 SQL 核对）")
    void updateSetting_shouldPersistToDatabase() throws Exception {
        String siteName = "站点" + mark;

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson(siteName, "公告" + mark, true,
                                "京ICP备12345678号-1", "© 2026 " + mark, 20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 断言落到数据库这一列的原生类型上（0/1 是 tinyint，不是布尔）
        assertEquals(siteName, rawValue("site_name", String.class));
        assertEquals("公告" + mark, rawValue("announcement", String.class));
        assertEquals(1, (int) rawValue("comment_enabled", Integer.class), "库里存的是 1，不是 true");
        assertEquals("京ICP备12345678号-1", rawValue("icp_number", String.class));
        assertEquals("© 2026 " + mark, rawValue("copyright", String.class));
        assertEquals(20, (int) rawValue("page_size", Integer.class));

        // 而且不会插出第二行
        assertEquals(1, countRows(), "站点设置表永远只有一行");
    }

    @Test
    @DisplayName("④ 保存之后前台立刻看到新值（缓存被推进版本号作废）")
    void updateSetting_shouldRefreshPublicGet() throws Exception {
        String before = settingService.get().getSiteName();
        assertNotNull(before, "前置条件：先读一次，把缓存建起来");

        String siteName = "新站点" + mark;
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson(siteName, null, false, null, null, 5)))
                .andExpect(jsonPath("$.code").value(200));

        SettingVO after = settingService.get();
        assertEquals(siteName, after.getSiteName(), "保存之后前台应当立刻是新站点名（缓存已失效）");
        assertFalse(after.getCommentEnabled(), "评论开关也要立刻生效");
        assertEquals(5, after.getPageSize());

        // 清掉的字段在 VO 里应当是 null（前端的语义是"这一块不渲染"）
        assertNull(after.getAnnouncement());
        assertNull(after.getIcpNumber());
        assertNull(after.getCopyright());
    }

    @Test
    @DisplayName("⑤ 站点名：空白 / 超长（51）-> 400（校验与列长度一致）")
    void updateSetting_invalidSiteName_shouldReturn400() throws Exception {
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("   ", null, true, null, null, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("长".repeat(51), null, true, null, null, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("站点名最长 50 字")));
    }

    @Test
    @DisplayName("⑥ 公告：500 字合法、501 被拒；清空（空串）真的写成 NULL")
    void updateSetting_announcementBoundaries() throws Exception {
        String longest = "公".repeat(500);
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公告边界" + mark, longest, true, null, null, 10)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(longest, rawValue("announcement", String.class), "刚好 500 字应当收下");

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公告超长" + mark, "公".repeat(501), true, null, null, 10)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("公告最长 500 字")));

        // 清空：公告会"下掉"，前端据此整块不渲染
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公告清空" + mark, "", true, null, null, 10)))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(rawValue("announcement", String.class), "清空公告必须真的写成 NULL");
    }

    @Test
    @DisplayName("⑦ 页脚两行：备案号与版权能填能清；备案号不做格式校验（各省编号形态不同）")
    void updateSetting_footerFields() throws Exception {
        // 备案号不做格式校验：带短横线、带省份简称、甚至公安备案号都合法
        for (String icp : new String[]{"京ICP备12345678号-1", "沪ICP备2026000000号-2", "苏公网安备32010000000000号"}) {
            mockMvc.perform(put("/admin/setting")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(settingJson("页脚" + mark, null, true, icp, "© 2026 亿轨星途", 10)))
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals(icp, rawValue("icp_number", String.class), "这种形态的备案号应当被接受：" + icp);
        }

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("备案超长" + mark, null, true, "备".repeat(51), null, 10)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("备案号最长 50 字")));

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("版权超长" + mark, null, true, null, "权".repeat(201), 10)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("版权文案最长 200 字")));

        // 两行都能清掉（换域名要换备案号；不想显示版权就去掉它）
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("页脚清空" + mark, null, true, "", "", 10)))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(rawValue("icp_number", String.class), "清空备案号必须真的写成 NULL");
        assertNull(rawValue("copyright", String.class), "清空版权必须真的写成 NULL");
    }

    @Test
    @DisplayName("⑧ 每页条数：上限必须跟着文章接口走（50 收下、51 与 0 被拒）")
    void updateSetting_pageSizeBoundaries() throws Exception {
        int max = (int) ArticleQuery.MAX_PAGE_SIZE;

        // 上限那个值必须【恰好】是文章接口的上限，不多也不少：
        // 接口那边超过会被静默夹到 50，所以这里允许更大的话，
        // 站长会得到一个"设置成功了但首页还是只列 50 篇"的开关，且不会有任何报错
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("每页上限" + mark, null, true, null, null, max)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(max, (int) rawValue("page_size", Integer.class), "接口上限那个值应当被接受");

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("每页超限" + mark, null, true, null, null, max + 1)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("每页条数最多 " + max)));

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("每页为零" + mark, null, true, null, null, 0)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("每页条数至少 1")));

        // 下界也接受：1 条一页是合法的（虽然没人这么用，但它是有效取值）
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("每页一条" + mark, null, true, null, null, 1)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(1, (int) rawValue("page_size", Integer.class));
    }

    @Test
    @DisplayName("⑨ 评论开关两个方向都要对：true -> 库里 1，false -> 库里 0，且 VO 里是布尔")
    void updateSetting_commentEnabledConversion() throws Exception {
        // 这个字段是本模块唯一一个"实体与接口类型不一致"的地方
        // （库里 0/1 便于用 SQL 排查，接口给布尔是因为前端控件就是 el-switch）。
        // 【所以两个方向都要断言】：只测一个方向的话，
        // 把转换写反（1 → false、0 → true）也能全绿 —— 而这个开关写反的后果是
        // "关闭评论之后前台反而开着"，属于不会报错只会做错事的一类
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("开关开" + mark, null, true, null, null, 10)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(SiteSetting.COMMENT_ENABLED, (int) rawValue("comment_enabled", Integer.class));
        assertTrue(settingService.get().getCommentEnabled(), "库里是 1，VO 里应当是 true");

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("开关关" + mark, null, false, null, null, 10)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(SiteSetting.COMMENT_DISABLED, (int) rawValue("comment_enabled", Integer.class));
        assertFalse(settingService.get().getCommentEnabled(), "库里是 0，VO 里应当是 false");

        // 少传这个字段要被拒（而不是"默认当成关闭"—— 那太危险了）
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("开关没传" + mark, null, null, null, null, 10)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("评论开关不能为空")));
    }

    @Test
    @DisplayName("⑩ 一次把三个可选字段全清空 -> 库里真的都是 NULL（守「用 LambdaUpdateWrapper」）")
    void updateSetting_clearAllOptionalFields_shouldActuallySetNull() throws Exception {
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("填满" + mark, "有公告", true,
                                "京ICP备12345678号-1", "© 2026", 30)))
                .andExpect(jsonPath("$.code").value(200));

        // 全部清空（提交空串 —— 前端清空输入框就长这样）。
        // 用 updateById 的话这些 null 字段会被跳过，页面上那些内容永远删不掉，
        // 用户看到的是"清空之后一刷新又回来了"，而且没有任何报错
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("全清" + mark, "", true, "", "", 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(rawValue("announcement", String.class), "公告应当被清成 NULL");
        assertNull(rawValue("icp_number", String.class), "备案号应当被清成 NULL");
        assertNull(rawValue("copyright", String.class), "版权应当被清成 NULL");
        assertEquals("全清" + mark, rawValue("site_name", String.class), "站点名是必填，只更新不置空");
    }

    // ================================================================
    //  三、单条记录特有的不变量
    // ================================================================

    @Test
    @DisplayName("⑪ 反复保存 -> 永远只有一行（不会插出第二行）")
    void updateSetting_repeatedly_shouldKeepExactlyOneRow() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(put("/admin/setting")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(settingJson("第 " + i + " 次" + mark, null, true, null, null, 10)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals(1, countRows(), "第 " + i + " 次保存之后仍然只能有一行");
        }
    }

    @Test
    @DisplayName("⑫ 那一行被物理删掉之后：GET 仍 200 给默认值，保存能把它写回来")
    void missingRow_shouldSelfHealInsteadOfBreakingEveryPage() throws Exception {
        jdbcTemplate.update("DELETE FROM site_setting");
        clearSettingCache();
        assertEquals(0, countRows(), "前置条件：那一行确实没了");

        // 【第一半：整站的外壳不能因此崩掉】这份数据是每一页都要读的
        // （页眉页脚 / 评论开关 / 首页分页），所以这里更不能 404 或 500
        mockMvc.perform(get("/setting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        SettingVO shell = settingService.get();
        // 评论开关必须有能用的默认值：它为 null 的后果不是"这块不显示"，
        // 而是前端不知道该不该渲染评论框（两边猜的方向还可能相反）——
        // 那是把"配置缺失"升级成了"功能损坏"
        assertEquals(Boolean.TRUE, shell.getCommentEnabled(), "缺行时评论开关应当默认开着");
        // 展示类字段留 null：前端的语义就是"这一块不渲染"，正好是这时候该有的表现
        assertNull(shell.getSiteName(), "缺行时站点名应当是 null（由前端回落到自己的默认常量）");
        assertNull(shell.getIcpNumber(), "缺行时不该编一个备案号出来");
        // ⚠️ 每页条数也留 null，而且这是【有意】的：那个默认值（12）是前端的排版决策
        //    （3 列瀑布流 4 行），后端不再写一份 —— 两边各写一个数字最容易漂移，
        //    而且它和 ArticleQuery.DEFAULT_PAGE_SIZE(10) 语义不同、极易被混为一谈。
        //    留 null 也是安全的：ArticleQuery 会把 null 的 size 当成"没传"用默认值
        assertNull(shell.getPageSize(), "缺行时每页条数应当是 null（默认值属于前端，后端不重复写一份）");

        // 【第二半：后台保存要把这一行救回来】，而不是静静地什么都不做
        // （返回 200 说"保存成功"、库里却没有任何变化，是最糟的一种失败）
        String siteName = "救回来" + mark;
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson(siteName, "自愈后的公告", false, null, null, 15)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, countRows(), "自愈之后应当恰好一行（不是两行）");
        assertEquals(SiteSetting.SINGLE_ROW_ID,
                jdbcTemplate.queryForObject("SELECT id FROM site_setting", Long.class),
                "写回来的那一行 id 必须是 1");
        assertEquals(siteName, rawValue("site_name", String.class));
        // 自愈写回来的那一行，各字段也必须是这次提交的值（不是默认值）
        assertEquals("自愈后的公告", rawValue("announcement", String.class));
        assertEquals(0, (int) rawValue("comment_enabled", Integer.class), "这次提交是关闭评论");
        assertEquals(15, (int) rawValue("page_size", Integer.class));
        assertEquals(siteName, settingService.get().getSiteName(), "前台随后就能读到它");
    }

    @Test
    @DisplayName("⑬ 站点设置没有新建接口：POST /admin/setting 是真 405（不是静默成功）")
    void postSetting_shouldReturn405() throws Exception {
        // 【为什么专门测这个】"只有一份数据"这件事在接口形状上是真的：
        // 只有 PUT，没有 POST。不测的话，将来有人顺手加一个 POST，
        // 库里就可能出现第二行，而所有"只有一份"的假设都会悄悄失效
        mockMvc.perform(post("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("想新建" + mark, null, true, null, null, 10)))
                // 405 是【真状态码】：地址对、方法用错属于"请求本身有问题"，
                // 与业务错误（HTTP 200 + body.code）分区处理，见 README「统一返回与错误处理」
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));

        assertEquals(1, countRows(), "POST 被拒之后库里仍然只有一行");
    }

    // ================================================================
    //  四、权限
    // ================================================================

    @Test
    @DisplayName("⑭ 权限：保存接口对游客 403、对无 token 401；读接口对谁都开放")
    void adminSettingApi_shouldRequireAdmin() throws Exception {
        String body = settingJson("游客偷偷改的", null, true, null, null, 10);

        mockMvc.perform(put("/admin/setting")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        // 读接口是公开的 —— 这条同时守住"别把 /setting 顺手改成要登录"：
        // 页脚的备案号是合规信息，未登录访客也必须看得到
        mockMvc.perform(get("/setting")).andExpect(status().isOk());
        mockMvc.perform(get("/setting").header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());
    }

    // ================================================================
    //  五、缓存
    // ================================================================

    @Test
    @DisplayName("⑮ 读接口走缓存：绕过 Service 直接改库 -> 读到的还是缓存里那份")
    void getSetting_shouldServeFromCache() {
        settingService.get();
        assertNotNull(awaitSettingKeyWithVersion(contentCacheVersion.current(), 2000),
                "第一次读之后 Redis 里应当出现 setting:detail 的缓存 key");

        String signed = "绕过Service改的" + mark;
        // 绕过 Service 直接改库：Mapper 上没有任何缓存注解，所以缓存不会失效
        jdbcTemplate.update("UPDATE site_setting SET site_name = ? WHERE id = 1", signed);

        assertNotEquals(signed, settingService.get().getSiteName(),
                "这次应当直接吃缓存，读不到绕过 Service 改的值");
    }

    @Test
    @DisplayName("⑯ 保存推进版本号；缓存 key 带当前版本号且有 TTL")
    void updateSetting_shouldBumpVersionAndCacheKeyShouldCarryVersion() throws Exception {
        settingService.get();
        long versionBefore = Long.parseLong(contentCacheVersion.current());

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("推进版本" + mark, null, true, null, null, 10)))
                .andExpect(jsonPath("$.code").value(200));

        assertTrue(Long.parseLong(contentCacheVersion.current()) > versionBefore,
                "保存之后版本号应当推进（否则前台的站点名/页脚要等 TTL 才更新）");

        // 版本号变了 → 刚读的缓存 key 已经拼不出来 → 重新读会写入一条【新版本】的 key。
        // ⚠️ 这里不能断言"有且只有一条 key"：老版本那条还在 Redis 里（等 TTL 自然过期）；
        //    也不能只等"出现任意一条 key"—— 老 key 早就在了，会立刻满足条件。
        //    所以要等的是【带当前版本号】的那一条（与 AboutTest 同一个坑）
        settingService.get();
        String key = awaitSettingKeyWithVersion(contentCacheVersion.current(), 2000);
        assertNotNull(key, "应当出现一条带当前版本号的缓存 key");

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "缓存必须有 TTL 兜底，实际剩余秒数=" + ttl);
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /**
     * 拼保存站点设置的请求体。
     *
     * 【null 与空串是两种输入，必须分得清】
     *   · 空串 = "我要清空这一栏"（前端清空输入框就长这样）
     *   · null = "本次不提交这个字段"（用于测 @NotNull / 缺字段那几条）
     * 对可选文本字段来说两者最终都会被归一化成 NULL，但走的校验路径不同；
     * 而 commentEnabled / pageSize 是必填，缺了要被拒 —— 所以这个区别必须能表达出来。
     */
    private String settingJson(String siteName, String announcement, Boolean commentEnabled,
                               String icpNumber, String copyright, Integer pageSize) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"siteName\":\"").append(siteName == null ? "" : siteName).append("\"");
        if (announcement != null) {
            sb.append(",\"announcement\":\"").append(announcement).append("\"");
        }
        if (commentEnabled != null) {
            sb.append(",\"commentEnabled\":").append(commentEnabled);
        }
        if (icpNumber != null) {
            sb.append(",\"icpNumber\":\"").append(icpNumber).append("\"");
        }
        if (copyright != null) {
            sb.append(",\"copyright\":\"").append(copyright).append("\"");
        }
        if (pageSize != null) {
            sb.append(",\"pageSize\":").append(pageSize);
        }
        return sb.append("}").toString();
    }

    /**
     * 读取 classpath 下的文本文件 —— 用来核对【迁移脚本 / 配置文件原文】，而不是解析结果。
     * （与 ProfileProdConfigTest 里那个同名方法同一个做法；② 用它避开"库里此刻的值
     * 取决于测试执行顺序"这个坑。）
     */
    private String readClasspathFile(String name) {
        try (var in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("classpath 下找不到 " + name);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 " + name + " 失败", e);
        }
    }

    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM site_setting", Integer.class);
        return count == null ? 0 : count;
    }

    /** 直查那一行的某一列（这张表只有一行，所以不需要 id 条件） */
    private <T> T rawValue(String column, Class<T> type) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM site_setting", type);
    }

    private User insertUser(String usernamePrefix, String role) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("站点设置测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    /**
     * 轮询等待"带指定版本号的那条缓存 key"出现。
     *
     * 【为什么要轮询】Spring Data Redis 的写入是异步发出的（连接池上另一条连接），
     * 方法返回时 KEYS 可能还看不到它 —— 直接断言会偶发变红（这个坑在 ArticleCacheTest 里踩过）。
     * 【为什么条件里要带版本号】见 ⑯ 的注释：老 key 还在，按"有 key"当条件会立刻满足。
     */
    private String awaitSettingKeyWithVersion(String version, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Set<String> keys = redis.keys(SETTING_CACHE_PREFIX + "*");
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
