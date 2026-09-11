package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
 *   而这里七个字段各自驱动界面上很不一样的一处：
 *     站点名 → 页眉页脚与标题、公告 → 首页那一条、评论开关 → 文章页的评论框、
 *     两个备案号（ICP 与公安网安）与版权 → 页脚、每页条数 → 首页列表请求的 size。
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

    /** ⑰ 要造一篇已发布的文章（评论必须挂在存在的文章上） */
    @Autowired
    private ArticleMapper articleMapper;

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
    @DisplayName("② 迁移已经把那一行插好了：库里恰好一行、id = 1；种子值读【迁移脚本原文】核对"
            + "（V13 新加的列同样在这一条里核对：结构 + 没有种子值）")
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

        // ---- V13（公安网安备案号）：先把上面这套断言"逐条对照到新列上" ----
        //
        // ⚠️ V13 是一条 ADD COLUMN，所以它只有两件事要断言：
        //   ① 库里真的多出这一列、且形状对（varchar(50)、可空、紧跟在 icp_number 后面）
        //   ② 脚本里【没有】任何种子值
        //
        // ① 用 information_schema 查【列的定义】，而不是 rawValue("police_number") 查值：
        //    与上面那两条 DB 断言同一类 —— "列存在、类型、位置"是与谁先跑无关的结构事实，
        //    而"列里此刻是什么值"会被 OperationLogTest ⑳ 这类提交型用例改掉（见上面那一大段）。
        //    这样既证明了"V13 真的执行了"（Flyway 静默失效是踩过的坑，
        //    见 ArticleIndexTest 里那条 flyway_schema_history 断言），又不碰会漂的东西。
        List<Map<String, Object>> policeColumn = jdbcTemplate.queryForList(
                "SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, ORDINAL_POSITION"
                        + " FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'site_setting'"
                        + " AND COLUMN_NAME = 'police_number'");
        assertEquals(1, policeColumn.size(),
                "V13 之后 site_setting 应当有 police_number 这一列 —— 查不到说明这条迁移没真正执行");
        assertEquals("varchar", policeColumn.get(0).get("DATA_TYPE"));
        assertEquals(50L, ((Number) policeColumn.get(0).get("CHARACTER_MAXIMUM_LENGTH")).longValue(),
                "长度 50 与 icp_number 一致（DTO 的 @Size、Service 的常量、列宽三处同一个数字）");
        assertEquals("YES", policeColumn.get(0).get("IS_NULLABLE"),
                "必须可空：留空 = 页脚不显示公安备案那一行（与 icp_number 同一条规则）");
        Long icpPosition = jdbcTemplate.queryForObject(
                "SELECT ORDINAL_POSITION FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'site_setting'"
                        + " AND COLUMN_NAME = 'icp_number'", Long.class);
        assertEquals(icpPosition + 1,
                ((Number) policeColumn.get(0).get("ORDINAL_POSITION")).longValue(),
                "AFTER icp_number：两个备案号在表里挨着，SELECT * 核对时一眼能看出是一对");
        // 列顺序断言用"+1"来表达，而不是写死一个绝对位置（比如 6）：
        // 写死的话，将来在它前面再加一列，这条断言会因为"与本次改动无关的原因"变红

        // ② "没有种子值"：读 V13 脚本原文核对（与上面那条"读 V12 原文"同一个做法）
        String v13 = readClasspathFile("db/migration/V13__add_police_number_to_site_setting.sql");
        assertTrue(v13.contains("ADD COLUMN `police_number` varchar(50) DEFAULT NULL"),
                "V13 应当加一列 police_number varchar(50) DEFAULT NULL");
        assertTrue(v13.contains("AFTER `icp_number`"),
                "新列的位置由 AFTER icp_number 指定（理由：两个备案号挨着放）");
        // ⚠️ 判断"有没有种子值"之前必须先把【注释行】剥掉：这份脚本的中文注释里
        //    恰恰写了"不要在这里补一条 UPDATE …"，直接对全文 contains("UPDATE")
        //    会被自己的说明文字绊倒 —— 那种失败信息最误导人（脚本明明是干净的）
        String v13Sql = v13.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        assertFalse(v13Sql.contains("INSERT"),
                "V13 不该 INSERT：备案号是站点主体相关信息，该由站长在后台自己填");
        assertFalse(v13Sql.contains("UPDATE"),
                "V13 也不该 UPDATE 出种子值：它属于备案材料，不该出现在公开仓库的迁移脚本里"
                        + "（与 V12 里 icp_number 留空是同一个理由）；留 NULL 才保证页脚外观不变");
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
    //  六、评论总开关的「后端强制」
    // ================================================================

    @Test
    @DisplayName("⑰ 评论总开关在后端真的生效：关掉之后接口拒绝新评论，打开之后又能发")
    void commentSwitch_shouldBeEnforcedByBackend() throws Exception {
        // 【为什么这条用例在这个类而不是 CommentTest】
        //   它验证的不是"评论怎么存"，而是"站点设置里那个开关真的管住了接口"——
        //   即这个功能的端到端效果。失败时一眼能看出该查设置那条链路。
        Long articleId = insertPublishedArticle();
        int before = commentCount(articleId);

        // ---- ① 开着（迁移的种子值就是 1）：能发 ----
        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson(articleId, "开着的时候" + mark)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(before + 1, commentCount(articleId), "开着时应当真的写进一条");

        // ---- ② 关掉开关：后端必须拒绝 ----
        setCommentEnabled(false);

        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson(articleId, "关掉之后" + mark)))
                // HTTP 仍是 200，业务码是 403 —— 本项目的业务错误统一走
                // "HTTP 200 + body.code"，见 README「统一返回与错误处理」
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("评论已关闭")));

        // ⚠️ 关键在于这一条：断言【库里没有多出记录】，而不是只看接口返回。
        //    "只藏前端表单、接口照收"那种假开关，光看返回码是看不出来的
        assertEquals(before + 1, commentCount(articleId), "关掉之后不该有任何新评论落库");

        // ---- ③ 再打开：又能发（证明开关是双向的，而不是"关了之后就再也开不开"）----
        setCommentEnabled(true);

        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson(articleId, "重新打开" + mark)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(before + 2, commentCount(articleId), "重新打开之后应当又能写进去");
    }

    // ================================================================
    //  七、公安网安备案号（V13 新增的那一列）
    //
    //  【为什么整段放在最后，而不是插进上面"逐字段"那一节】
    //    上一个字段（ICP 备案号）的用例在 ⑦ / ⑩ 里，而它是和版权挤在一条里写的；
    //    这里既不回去改动那两条已通过的用例，也不打乱 ① … ⑰ 的编号 ——
    //    新增的东西整段追加，审查时一眼能圈出"这次只动了这一段"。
    //    内容是同一套思路：能填、能读、能清空、卡长度、缓存生效、兜底不编值。
    // ================================================================

    @Test
    @DisplayName("⑱ 公安备案号：保存 -> 库里那一列真的有值，公开 GET 也读得到")
    void updateSetting_policeNumber_shouldPersistAndBeReadable() throws Exception {
        // 测试数据用与 ⑦ 同形的【假号】（那个省简称 + 14 位数字的形态是真的，
        // 数字是编的）：真实备案号属于站点主体的备案材料，不该进仓库 ——
        // 这条规矩对迁移脚本和测试数据是同一条
        // ⚠️ 公安号里那个【中间的空格】是保留的（真实形态就是"苏公网安备 3201…号"）：
        //    normalizeOptional 只 trim【首尾】空白，不会去动中间的 —— 要是有人顺手写成
        //    replaceAll("\\s", "")，页脚上拼给公安平台查询页的号就会和备案时的号不一致
        String icp = "京ICP备12345678号-1";
        String police = "苏公网安备 32010000000000号";

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公安排" + mark, null, true, icp, police, "© 2026 亿轨星途", 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // ① 断言落到数据库这一列的原生值上（与 ③ 同一条规矩：不能只看接口返回，
        //    接口返回的可能是"内存里拼的那份"，写没写进库要单独证明）
        assertEquals(police, rawValue("police_number", String.class),
                "公安备案号必须真的写进 police_number 这一列");

        // ② 两个备案号【各自独立】：写公安那个号不该动到 ICP 那一列 ——
        //    它们不是同一个字段的两种写法，而是两套备案体系（见 V13 脚本里的说明）
        assertEquals(icp, rawValue("icp_number", String.class),
                "保存公安备案号不该把 ICP 备案号连带改掉");

        // ③ 前台读得到：页脚渲染用的就是 GET /setting 这一份（公开、走缓存）
        SettingVO vo = settingService.get();
        assertEquals(police, vo.getPoliceNumber(), "公开读应当拿到刚保存的公安备案号");
        assertEquals(icp, vo.getIcpNumber(), "两个号在 VO 里同样是各自独立的两项");
    }

    @Test
    @DisplayName("⑲ 公安备案号：提交空串能被清空（库里与接口都变回 null）")
    void updateSetting_policeNumber_shouldBeClearable() throws Exception {
        // 【为什么"能清空"要单独有用例】它是"用 updateById 会清不掉"那个坑的护栏：
        // updateById 会【跳过 null 字段】，于是表现是"后台清空了、页脚仍挂着旧备案号"，
        // 而接口高高兴兴返回 200 —— 换主体、换域名时第一个要做的动作就是清旧号。
        // ⑦ 用 icpNumber 守过这条，这里用新列再守一次：它是【另一条 SET 语句】，
        // 漏写一处就是漏一整列（不是同一个写法的复制粘贴问题）。
        String police = "苏公网安备32010000000000号";
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公安排填上" + mark, null, true, null, police, null, 10)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(police, rawValue("police_number", String.class), "前置条件：先把它填上");

        // 空串 = "我要清空这一栏"（前端清空输入框就长这样，见 settingJson 的注释）
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公安排清空" + mark, null, true, null, "", null, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(rawValue("police_number", String.class), "清空公安备案号必须真的写成 NULL");
        assertNull(settingService.get().getPoliceNumber(),
                "接口里也必须是 null —— 前端据此决定页脚不渲染公安那一行");
    }

    @Test
    @DisplayName("⑳ 公安备案号：50 字收下、51 被拒（与 DTO 校验、列长度三处一致）")
    void updateSetting_policeNumberBoundaries() throws Exception {
        // 上限必须【恰好】是列宽：列是 varchar(50)，校验要是松到 60，
        // 会出现"提交成功、MySQL 静默截断"的偏差 —— 站长的号存进去少一截，
        // 而且不会有任何报错（与 ⑤ / ⑦ 守的是同一类问题）
        String longest = "备".repeat(50);
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公安边界" + mark, null, true, null, longest, null, 10)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(longest, rawValue("police_number", String.class), "刚好 50 字应当收下");

        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公安超长" + mark, null, true, null, "备".repeat(51), null, 10)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("公安备案号最长 50 字")));
    }

    @Test
    @DisplayName("㉑ 保存公安备案号之后前台立刻读到新值（缓存被推进版本号作废）")
    void updateSetting_policeNumber_shouldRefreshCachedGet() throws Exception {
        // 【为什么它和 ⑱ 不重复】⑱ 证明"写进库了"，这条证明"读出来的是新的"：
        // GET /setting 是走 Redis 的（@Cacheable，key = 内容缓存版本号）。
        // 漏掉 bump 的后果与 ⑮ 里那个"绕过 Service 改库"是同一个症状 ——
        // 库里明明是新的，页脚却要等缓存 TTL 到期才更新，而且没有任何报错。
        // 所以这里先【故意读一次】把旧值灌进缓存，再保存新值，最后要求读到的必须是新值。
        String before = "苏公网安备32010000000001号";
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公安缓存前" + mark, null, true, null, before, null, 10)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(before, settingService.get().getPoliceNumber(), "前置条件：先把旧值读进缓存");
        long versionBefore = Long.parseLong(contentCacheVersion.current());

        String updated = "苏公网安备32010000000002号";
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公安缓存后" + mark, null, true, null, updated, null, 10)))
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(updated, settingService.get().getPoliceNumber(),
                "保存之后前台读到的必须是新号，而不是缓存里那份旧的");
        assertTrue(Long.parseLong(contentCacheVersion.current()) > versionBefore,
                "保存应当推进内容缓存版本号（否则前台的页脚要等 TTL 才更新）");
    }

    @Test
    @DisplayName("㉒ 那一行不存在时：兜底值里 policeNumber 是 null（展示类字段不参与兜底）")
    void missingRow_shouldLeavePoliceNumberNull() throws Exception {
        // 与 ⑫ 是同一件事落在新字段上的那一条。规矩还是那两条：
        //   · 行为类（commentEnabled）必须给一个能用的值，否则前端不知道该不该渲染评论框
        //   · 展示类（备案号 / 版权 / 站点名）必须留 null —— 这里要是编一个备案号出来，
        //     等于在"站点根本没备案"的情况下让页脚挂出一个号，合规问题比"这一行不显示"严重得多
        jdbcTemplate.update("DELETE FROM site_setting");
        clearSettingCache();
        assertEquals(0, countRows(), "前置条件：那一行确实没了");

        mockMvc.perform(get("/setting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertNull(settingService.get().getPoliceNumber(), "缺行时不该编一个公安备案号出来");
        assertNull(settingService.get().getIcpNumber(), "ICP 备案号同样不参与兜底（与 ⑫ 一致）");

        // 顺手把 ⑫ 的"第二半"在新字段上验一遍：自愈写回的那一行也要带上本次提交的公安备案号
        // （漏了 setPoliceNumber 的后果是"自愈之后页脚少一行"，而且只有真出过事才暴露）
        String police = "苏公网安备32010000000003号";
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("公安自愈" + mark, null, true, null, police, null, 10)))
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, countRows(), "自愈之后仍然恰好一行");
        assertEquals(police, rawValue("police_number", String.class),
                "自愈写回的那一行必须带上本次提交的公安备案号");
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 把评论总开关设成指定值（走后台接口，顺便证明这条路径本身是通的） */
    private void setCommentEnabled(boolean enabled) throws Exception {
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingJson("开关测试" + mark, null, enabled, null, null, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 造一篇【已发布】的文章 —— 评论必须挂在一篇存在的、已发布的文章上
     * （草稿不能评论，见 CommentService）。
     */
    private Long insertPublishedArticle() {
        Article article = new Article();
        article.setTitle("评论开关测试文章 " + mark);
        article.setSummary("用于验证评论总开关是否真的管住了接口");
        article.setContent("正文");
        article.setStatus(1);
        article.setIsTop(0);
        article.setViewCount(0);
        article.setAuthorId(1L);
        articleMapper.insert(article);
        return article.getId();
    }

    private int commentCount(Long articleId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE article_id = ?", Integer.class, articleId);
        return count == null ? 0 : count;
    }

    private String commentJson(Long articleId, String nickname) {
        return "{\"articleId\":" + articleId + ",\"nickname\":\"" + nickname
                + "\",\"content\":\"评论开关测试内容\"}";
    }

    /**
     * 拼保存站点设置的请求体。
     *
     * 【null 与空串是两种输入，必须分得清】
     *   · 空串 = "我要清空这一栏"（前端清空输入框就长这样）
     *   · null = "本次不提交这个字段"（用于测 @NotNull / 缺字段那几条）
     * 对可选文本字段来说两者最终都会被归一化成 NULL，但走的校验路径不同；
     * 而 commentEnabled / pageSize 是必填，缺了要被拒 —— 所以这个区别必须能表达出来。
     *
     * 【policeNumber 也按同一条规则处理】它的位置在 icpNumber 与 copyright 之间，
     * 与数据库列里的先后顺序、与 SettingForm / SettingVO 的字段顺序保持一致：
     * 四层（库 / 实体 / 接口 / 测试）读起来是同一个顺序，加字段时不容易漏。
     */
    private String settingJson(String siteName, String announcement, Boolean commentEnabled,
                               String icpNumber, String policeNumber, String copyright, Integer pageSize) {
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
        if (policeNumber != null) {
            sb.append(",\"policeNumber\":\"").append(policeNumber).append("\"");
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
     * 【旧签名】重载：等价于"本次不提交 policeNumber 这个字段"。
     *
     * 【为什么保留这个六参版本，而不是把上面三十多处调用一次性改签名】
     *   ① 既有用例（① … ⑰）的语义本来就是"只提交这六个字段"，签名与语义一一对应；
     *      扩大签名会把一次"加一个字段"的改动变成横扫全文的 diff，
     *      里面夹着一大片与本次改动无关的噪音，审查时反而看不清真正改了什么
     *   ② 它委托给七参版本，两者拼出来的 JSON 只差"有没有 policeNumber"这一处 ——
     *      不会出现两套拼串逻辑各自漂移（那才是保留重载的真正风险点）
     */
    private String settingJson(String siteName, String announcement, Boolean commentEnabled,
                               String icpNumber, String copyright, Integer pageSize) {
        return settingJson(siteName, announcement, commentEnabled, icpNumber, null, copyright, pageSize);
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
