package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.favorite.dto.FavoriteVO;
import com.yigalaxy.yiguixingtu.favorite.entity.Favorite;
import com.yigalaxy.yiguixingtu.favorite.mapper.FavoriteMapper;
import com.yigalaxy.yiguixingtu.favorite.service.FavoriteService;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 收藏模块测试（FavoriteController / AdminFavoriteController / FavoriteServiceImpl）
 *
 * 【本类除了那套通用形状，重点盯的是 category 这个字段的语义】
 *   它是【自由文本的分组名】，不是 category 表的 id —— 这条区分必须有用例钉住，
 *   否则后来很容易有人"顺手"把它改成关联：
 *     · 分组名可以随便写（"面试题/八股"这种带斜杠的也合法，不做格式白名单）
 *     · 不填分组时是 NULL（= 未分组），不是空串
 *     · 同一个分组下可以有多条、清空分组只是让这条变成未分组
 *       ——【不会】去动别人，也【不会】因为"分组没了"而报错
 *   和分组的对照：url 是唯一的格式白名单字段（它会进 <a href>），
 *   其余字段只做长度与必填校验（只有会被浏览器解释的字段才需要格式白名单）。
 *
 * 【其余与 FriendLinkTest / ProjectTest 同构】前台只含显示中的、排序双段稳定、
 * 逻辑删除、清空可选字段真的写成 NULL、sort/status 缺省的两个方向、
 * 权限 401/403、缓存命中与失效。审计断言在 OperationLogTest 第⑰条
 * （本类 @Transactional 跑完回滚，AFTER_COMMIT 的审计永远等不到提交）。
 * =====================================================================
 */
class FavoriteTest extends AbstractIntegrationTest {

    /** 前台列表缓存在 Redis 里的完整前缀（与 RedisConfig 的 computePrefixWith 规则一致） */
    private static final String FAVORITE_CACHE_PREFIX = RedisConfig.CACHE_FAVORITE_LIST + ":v1:";

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private FavoriteMapper favoriteMapper;

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
    private User admin;
    private String adminToken;
    private String guestToken;

    @BeforeEach
    void setUp() {
        mark = "F" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        admin = insertUser("test_admin_fav", "ADMIN");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        // 真的插一个 GUEST：身份以数据库里的角色为准（给 ADMIN 签 GUEST token 依然是 ADMIN）
        User guest = insertUser("test_guest_fav", "GUEST");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        clearFavoriteCache();
    }

    @AfterEach
    void tearDown() {
        clearFavoriteCache();
    }

    private void clearFavoriteCache() {
        Set<String> keys = redis.keys(FAVORITE_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    //  一、前台公开列表
    // ================================================================

    @Test
    @DisplayName("① 游客不登录就能拿收藏列表，且【隐藏的那些不会出现】")
    void publicList_withoutToken_shouldExcludeHiddenFavorites() throws Exception {
        Favorite visible = insertFavorite("显示的收藏", 1, Favorite.STATUS_VISIBLE);
        Favorite hidden = insertFavorite("隐藏的收藏", 2, Favorite.STATUS_HIDDEN);

        mockMvc.perform(get("/favorite/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        assertNotNull(findVisibleByTitle(visible.getTitle()), "显示中的收藏应当在前台列表里");
        assertNull(findVisibleByTitle(hidden.getTitle()),
                "隐藏的收藏不该出现在前台列表里（过滤写在 SQL 里，不是交给前端）");
    }

    @Test
    @DisplayName("② 排序：先按 sort 升序；sort 相同时按 id 升序（顺序必须稳定）")
    void publicList_shouldOrderBySortThenId() {
        Favorite first = insertFavorite("同序甲", 5, Favorite.STATUS_VISIBLE);
        Favorite second = insertFavorite("同序乙", 5, Favorite.STATUS_VISIBLE);
        Favorite head = insertFavorite("排最前", 1, Favorite.STATUS_VISIBLE);

        List<String> titles = favoriteService.listVisible().stream()
                .map(FavoriteVO::getTitle)
                .toList();

        // 先确认三条都在：indexOf 找不到时返回 -1，而 -1 比任何下标都小，
        // 少了这条前置断言，下面的排序断言在"数据根本没查出来"时也会绿
        assertTrue(titles.contains(head.getTitle()) && titles.contains(first.getTitle())
                        && titles.contains(second.getTitle()),
                "前置条件：三条都应当出现在前台列表里，实际=" + titles);

        assertTrue(titles.indexOf(head.getTitle()) < titles.indexOf(first.getTitle()), "sort 小的在前");
        assertTrue(titles.indexOf(first.getTitle()) < titles.indexOf(second.getTitle()),
                "sort 相同的按 id 升序（先插的在前）");
    }

    @Test
    @DisplayName("③ 响应里带齐卡片需要的字段（地址 / 备注 / 分组 / 排序 / 创建时间）")
    void publicList_shouldCarryFieldsForRendering() throws Exception {
        String title = "字段齐全" + mark;
        Long id = createViaApi(favoriteJson(title, "https://example.com/tool",
                "备注文本", "工具", 1, 1));

        String body = mockMvc.perform(get("/favorite/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode found = findNodeByTitle(body, title);
        assertNotNull(found, "响应里应当能找到刚建的收藏");
        assertEquals(id, found.path("id").asLong());
        assertEquals("https://example.com/tool", found.path("url").asText());
        assertEquals("备注文本", found.path("description").asText());
        assertEquals("工具", found.path("category").asText(),
                "分组名要原样返回（后端不把它转成分组对象，前端自己 groupBy）");
        assertEquals(1, found.path("sort").asInt());
        assertEquals(1, found.path("status").asInt());
        assertTrue(found.hasNonNull("createTime"), "创建时间要在响应里");
    }

    // ================================================================
    //  二、后台新建（正常路径 + 边界）
    // ================================================================

    @Test
    @DisplayName("④ 新建收藏 -> 200，库里真有这行（用原生 SQL 逐字段核对）")
    void createFavorite_withValidForm_shouldPersistToDatabase() throws Exception {
        String title = "新收藏" + mark;

        Long id = createViaApi(favoriteJson(title, "https://example.com/article/1",
                "值得反复看", "文章", 3, 1));

        assertEquals(title, rawValue(id, "title", String.class));
        assertEquals("https://example.com/article/1", rawValue(id, "url", String.class));
        assertEquals("值得反复看", rawValue(id, "description", String.class));
        assertEquals("文章", rawValue(id, "category", String.class));
        assertEquals(3, rawValue(id, "sort", Integer.class));
        assertEquals(Favorite.STATUS_VISIBLE, rawValue(id, "status", Integer.class));
        assertEquals(0, rawValue(id, "deleted", Integer.class), "新建的行 deleted 必须是 0");
    }

    @Test
    @DisplayName("⑤ 不传分组 / 备注 -> 存 NULL（未分组）；sort 默认 0、status 默认 1")
    void createFavorite_withoutOptionalFields_shouldUseDefaults() throws Exception {
        String title = "默认值" + mark;

        // 只传必填的标题与地址
        String body = mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson(title, "https://example.com/only-required", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        Long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
        assertEquals(0, rawValue(id, "sort", Integer.class), "不传 sort 应当按 0 处理");
        assertEquals(Favorite.STATUS_VISIBLE, rawValue(id, "status", Integer.class),
                "不传 status 应当按 1(显示) —— 管理员自己录的收藏，录完就是要展示的");
        assertNull(rawValue(id, "category", String.class),
                "不填分组应当是 NULL（= 未分组），而不是空串");
        assertNull(rawValue(id, "description", String.class), "不填备注应当是 NULL");

        // 空串（前端清空输入框）也要归一化成 NULL，不能存进去一个空串
        String body2 = mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson(title + "-空串", "https://example.com/blank", "", "", null, null)))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        Long id2 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body2).get("data").asLong();
        assertNull(rawValue(id2, "category", String.class),
                "提交空串时也必须存 NULL（否则库里会同时存在 \"\" 和 NULL 两种\"没有值\"）");
        assertNull(rawValue(id2, "description", String.class));
    }

    @Test
    @DisplayName("⑥ 标题空白 / 超长（101）-> 400（校验与列长度一致）")
    void createFavorite_invalidTitle_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("   ", "https://example.com/a", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("长".repeat(101), "https://example.com/a", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("最长 100 字")));
    }

    @Test
    @DisplayName("⑦ 地址为空 / 裸域名 / javascript: / 超长 -> 400（收藏的地址是唯一必填的地址）")
    void createFavorite_invalidUrl_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("地址为空", "", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("裸域名", "example.com", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 这条会被前台渲染成 <a href="javascript:...">，点一下就执行脚本
        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("脚本地址", "javascript:alert(1)", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        String tooLong = "https://" + "a".repeat(248);
        assertEquals(256, tooLong.length(), "前置条件：这条地址刚好是 256 位");
        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("超长地址", tooLong, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑧ 分组名是自由文本：带斜杠/空格/中文都合法，只卡长度（51 -> 400）")
    void createFavorite_categoryIsFreeText_shouldOnlyCheckLength() throws Exception {
        // 【这条用例守的是 category 的语义】它只是一个标签式的分组名，
        // 不是 category 表的 id，也不该有"格式规则" ——
        // 「面试题/八股」这种带斜杠的写法在真实使用里非常常见，
        // 一旦给它加上格式白名单，用户会莫名其妙地被拦住
        String title = "自由分组" + mark;
        Long id = createViaApi(favoriteJson(title, "https://example.com/free",
                null, "面试题/八股 & 手写题", 1, 1));
        assertEquals("面试题/八股 & 手写题", rawValue(id, "category", String.class));

        // 只卡长度：51 个字
        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("分组超长", "https://example.com/long-cat",
                                null, "分".repeat(51), null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("分组名最长 50 字")));
    }

    @Test
    @DisplayName("⑨ 备注超长（501）-> 400；非法状态（2 / -1）-> 400")
    void createFavorite_descriptionTooLongAndInvalidStatus_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("备注超长", "https://example.com/d",
                                "备".repeat(501), null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("备注最长 500 字")));

        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("状态2", "https://example.com/s", null, null, null, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("0(隐藏) 或 1(显示)")));

        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("状态-1", "https://example.com/s2", null, null, null, -1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑩ 同一个分组下可以有多条；不同分组共存；未分组的用 null 表示")
    void createFavorite_sameCategory_shouldCoexist() throws Exception {
        Long first = createViaApi(favoriteJson("同组甲" + mark, "https://example.com/1", null, "工具", 1, 1));
        Long second = createViaApi(favoriteJson("同组乙" + mark, "https://example.com/2", null, "工具", 2, 1));
        Long other = createViaApi(favoriteJson("另组" + mark, "https://example.com/3", null, "视频", 3, 1));
        Long none = createViaApi(favoriteJson("未分组" + mark, "https://example.com/4", null, null, 4, 1));

        assertEquals("工具", rawValue(first, "category", String.class));
        assertEquals("工具", rawValue(second, "category", String.class),
                "同一个分组名下有多条是正常的（它不是唯一键，也不是外键）");
        assertEquals("视频", rawValue(other, "category", String.class));
        assertNull(rawValue(none, "category", String.class), "未分组就是 NULL");

        // 前台一次返回全部，前端按 category 分组 —— 后端不做 GROUP BY
        List<FavoriteVO> visible = favoriteService.listVisible();
        assertEquals(2, visible.stream().filter(vo -> "工具".equals(vo.getCategory())).count(),
                "分组信息原样带在每条记录上，前端 groupBy 即可");
    }

    // ================================================================
    //  三、后台编辑
    // ================================================================

    @Test
    @DisplayName("⑪ 编辑收藏 -> 库里更新；改名后前台立刻显示新标题")
    void updateFavorite_shouldPersistNewValues() throws Exception {
        Favorite favorite = insertFavorite("改前" + mark, 1, Favorite.STATUS_VISIBLE);

        String newTitle = "改后" + mark;
        mockMvc.perform(put("/admin/favorite/{id}", favorite.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson(newTitle, "https://example.com/new",
                                "新备注", "新分组", 9, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(newTitle, rawValue(favorite.getId(), "title", String.class));
        assertEquals("https://example.com/new", rawValue(favorite.getId(), "url", String.class));
        assertEquals("新备注", rawValue(favorite.getId(), "description", String.class));
        assertEquals("新分组", rawValue(favorite.getId(), "category", String.class));
        assertEquals(9, rawValue(favorite.getId(), "sort", Integer.class));

        assertNotNull(findVisibleByTitle(newTitle), "改名后前台应当是新标题");
        assertNull(findVisibleByTitle("改前" + mark), "旧标题不该再出现在前台列表里");
    }

    @Test
    @DisplayName("⑫ 清空分组与备注 -> 数据库里真的变成 NULL（守「用 LambdaUpdateWrapper」）")
    void updateFavorite_clearOptionalFields_shouldActuallySetNull() throws Exception {
        String title = "要清空" + mark;
        Long id = createViaApi(favoriteJson(title, "https://example.com/clear",
                "有备注", "有分组", 1, 1));
        assertEquals("有分组", rawValue(id, "category", String.class), "前置条件：分组有值");

        // 提交空串（前端清空输入框就长这样）—— 必须真的写成 NULL。
        // 用 updateById 的话 null 字段会被跳过，分组和备注原样留着，
        // 用户看到的是"清空之后一刷新又回来了"，而且不会有任何报错
        mockMvc.perform(put("/admin/favorite/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson(title, "https://example.com/clear", "", "", 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(rawValue(id, "category", String.class), "清空分组必须真的写成 NULL");
        assertNull(rawValue(id, "description", String.class), "清空备注同理");

        // 显式传 null（字段不出现）也要是同一个结果
        mockMvc.perform(put("/admin/favorite/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson(title, "https://example.com/clear", null, null, 1, 1)))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(rawValue(id, "category", String.class), "不传分组同样应当保持 NULL");
    }

    @Test
    @DisplayName("⑬ 编辑时 sort / status 缺省 -> 保持原值；显式传 0 才是想改成 0")
    void updateFavorite_withoutSortAndStatus_shouldKeepExistingValues() throws Exception {
        Favorite favorite = insertFavorite("保持原值" + mark, 7, Favorite.STATUS_HIDDEN);

        mockMvc.perform(put("/admin/favorite/{id}", favorite.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + favorite.getTitle()
                                + "\",\"url\":\"https://example.com/keep\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(7, rawValue(favorite.getId(), "sort", Integer.class),
                "不传 sort 应当保持原值 7，而不是被重置成 0");
        assertEquals(Favorite.STATUS_HIDDEN, rawValue(favorite.getId(), "status", Integer.class),
                "不传 status 应当保持原值（隐藏）");

        mockMvc.perform(put("/admin/favorite/{id}", favorite.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + favorite.getTitle()
                                + "\",\"url\":\"https://example.com/keep\",\"sort\":0}"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(0, rawValue(favorite.getId(), "sort", Integer.class), "显式传 0 就应当真的改成 0");
    }

    @Test
    @DisplayName("⑭ 把收藏改成隐藏 -> 前台立刻消失，后台列表仍看得到")
    void updateFavorite_toHidden_shouldDisappearFromPublicList() throws Exception {
        Favorite favorite = insertFavorite("先显示" + mark, 1, Favorite.STATUS_VISIBLE);
        assertNotNull(findVisibleByTitle(favorite.getTitle()), "前置条件：它本来前台可见");

        mockMvc.perform(put("/admin/favorite/{id}", favorite.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson(favorite.getTitle(), "https://example.com/hide",
                                null, null, 1, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(findVisibleByTitle(favorite.getTitle()),
                "改成隐藏之后前台必须立刻看不到（同时验证了写操作会推进缓存版本号）");
        assertNotNull(favoriteService.listAll().stream()
                        .filter(vo -> favorite.getTitle().equals(vo.getTitle()))
                        .findFirst().orElse(null),
                "后台列表要含隐藏的，否则管理员改不回来");
    }

    @Test
    @DisplayName("⑮ 编辑不存在的收藏 -> 404（业务错误码，HTTP 仍是 200）")
    void updateFavorite_notExist_shouldReturn404Code() throws Exception {
        mockMvc.perform(put("/admin/favorite/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson("不存在", "https://example.com/nope", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("收藏不存在")));
    }

    // ================================================================
    //  四、删除
    // ================================================================

    @Test
    @DisplayName("⑯ 删除收藏 -> 【逻辑删除】：物理行还在、deleted=1、Mapper 与前台都查不到")
    void deleteFavorite_shouldSoftDelete() throws Exception {
        Favorite favorite = insertFavorite("待删" + mark, 1, Favorite.STATUS_VISIBLE);

        mockMvc.perform(delete("/admin/favorite/{id}", favorite.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, countPhysicalRows(favorite.getId()), "逻辑删除只是 UPDATE，物理行必须还在");
        assertEquals(1, rawValue(favorite.getId(), "deleted", Integer.class), "deleted 应当被置成 1");
        assertNull(favoriteMapper.selectById(favorite.getId()), "带 @TableLogic 的查询应当看不到它");
        assertNull(findVisibleByTitle(favorite.getTitle()), "删掉之后前台列表里也不该再有它");
    }

    @Test
    @DisplayName("⑰ 删除不存在的收藏 / 重复删除 -> 404")
    void deleteFavorite_notExist_shouldReturn404Code() throws Exception {
        mockMvc.perform(delete("/admin/favorite/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        Favorite favorite = insertFavorite("删两次" + mark, 1, Favorite.STATUS_VISIBLE);
        mockMvc.perform(delete("/admin/favorite/{id}", favorite.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/admin/favorite/{id}", favorite.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ================================================================
    //  五、权限
    // ================================================================

    @Test
    @DisplayName("⑱ 权限：后台四个接口对游客 403、对无 token 401（一个都不能漏）")
    void adminFavoriteApis_shouldRequireAdmin() throws Exception {
        String body = favoriteJson("游客偷偷建的", "https://example.com/x", null, null, null, null);

        mockMvc.perform(get("/admin/favorite/list")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/favorite")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/favorite/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/admin/favorite/{id}", 1L)).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/favorite/list")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/favorite/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/favorite/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    // ================================================================
    //  六、缓存
    // ================================================================

    @Test
    @DisplayName("⑲ 前台列表走缓存：绕过 Service 直插 -> 列表里看不到（说明这次吃的是缓存）")
    void publicList_shouldServeFromCache() {
        favoriteService.listVisible();
        assertTrue(awaitFavoriteCacheKeys(1, 2000).size() > 0,
                "第一次调用之后 Redis 里应当出现 favorite:list 的缓存 key");

        Favorite sneaky = insertFavorite("绕过Service" + mark, 50, Favorite.STATUS_VISIBLE);

        assertNull(findVisibleByTitle(sneaky.getTitle()),
                "这次应当直接吃缓存，看不到绕过 Service 插进去的数据");
    }

    @Test
    @DisplayName("⑳ 新建 / 编辑 / 删除都会推进版本号 -> 前台列表立刻能看到变化")
    void everyWriteOperation_shouldBumpVersionAndRefreshPublicList() throws Exception {
        favoriteService.listVisible();

        long beforeCreate = contentVersion();
        String title = "会推进版本" + mark;
        Long id = createViaApi(favoriteJson(title, "https://example.com/v1", null, null, 1, 1));
        assertTrue(contentVersion() > beforeCreate, "新建之后版本号应当推进");
        assertNotNull(findVisibleByTitle(title), "新建之后前台应当立刻能看到它");

        long beforeUpdate = contentVersion();
        String renamed = title + "-改名后";
        mockMvc.perform(put("/admin/favorite/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson(renamed, "https://example.com/v2", null, null, 1, 1)))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(contentVersion() > beforeUpdate, "编辑之后版本号应当推进");
        assertNotNull(findVisibleByTitle(renamed), "改名之后前台应当立刻看到新标题");

        long beforeDelete = contentVersion();
        mockMvc.perform(delete("/admin/favorite/{id}", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(contentVersion() > beforeDelete, "删除之后版本号应当推进");
        assertNull(findVisibleByTitle(renamed), "删除之后前台应当立刻看不到它");
    }

    @Test
    @DisplayName("㉑ 缓存 key 里带当前版本号，且有 TTL")
    void favoriteCacheKey_shouldCarryVersionAndTtl() {
        favoriteService.listVisible();

        Set<String> keys = awaitFavoriteCacheKeys(1, 2000);
        assertEquals(1, keys.size(), "前台收藏列表只有一份，应当只有一条缓存 key，实际=" + keys);

        String key = keys.iterator().next();
        assertTrue(key.contains(contentCacheVersion.current()),
                "缓存 key 里应当带当前版本号（「写操作立刻失效」的全部原理），实际=" + key);

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "缓存必须有 TTL 兜底，实际剩余秒数=" + ttl);
    }

    // ================================================================
    //  工具方法
    // ================================================================

    private long contentVersion() {
        return Long.parseLong(contentCacheVersion.current());
    }

    /** 走接口新建一条收藏，返回 id */
    private Long createViaApi(String json) throws Exception {
        String body = mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
    }

    /** 直接写库造一条收藏（准备数据用；绕过 Service，所以也不会推进版本号） */
    private Favorite insertFavorite(String titlePrefix, int sort, int status) {
        Favorite favorite = new Favorite();
        favorite.setTitle(titlePrefix + "_" + mark);
        favorite.setUrl("https://example.com/" + mark);
        favorite.setSort(sort);
        favorite.setStatus(status);
        favorite.setDeleted(0);
        favoriteMapper.insert(favorite);
        return favorite;
    }

    /** 从【前台接口/服务】返回的列表里按标题找一条 */
    private FavoriteVO findVisibleByTitle(String title) {
        return favoriteService.listVisible().stream()
                .filter(vo -> title.equals(vo.getTitle()))
                .findFirst()
                .orElse(null);
    }

    /** 从响应体里按标题找出那一条（用来断言接口真的把字段吐出来了） */
    private com.fasterxml.jackson.databind.JsonNode findNodeByTitle(String responseBody, String title) throws Exception {
        for (com.fasterxml.jackson.databind.JsonNode node
                : new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody).get("data")) {
            if (title.equals(node.path("title").asText())) {
                return node;
            }
        }
        return null;
    }

    /** 拼请求体：null 表示"这个字段不出现在 JSON 里"（不传与传空串是两种输入） */
    private String favoriteJson(String title, String url, String description,
                                String category, Integer sort, Integer status) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"title\":\"").append(title == null ? "" : title).append("\",");
        sb.append("\"url\":\"").append(url == null ? "" : url).append("\"");
        if (description != null) {
            sb.append(",\"description\":\"").append(description).append("\"");
        }
        if (category != null) {
            sb.append(",\"category\":\"").append(category).append("\"");
        }
        if (sort != null) {
            sb.append(",\"sort\":").append(sort);
        }
        if (status != null) {
            sb.append(",\"status\":").append(status);
        }
        return sb.append("}").toString();
    }

    private int countPhysicalRows(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    /**
     * 直查原生列的值。
     * 【为什么不用 Mapper】@TableLogic 会自动加 deleted = 0，
     * 于是"逻辑删除那一行还在不在、deleted 是几"用 Mapper 查不出来。
     */
    private <T> T rawValue(Long id, String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM favorite WHERE id = ?", type, id);
    }

    private User insertUser(String usernamePrefix, String role) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("收藏测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    /** 轮询等缓存 key 出现（Spring Data Redis 的写入是异步发出的，直接断言会偶发变红） */
    private Set<String> awaitFavoriteCacheKeys(int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> keys = Set.of();
        while (System.currentTimeMillis() < deadline) {
            keys = redis.keys(FAVORITE_CACHE_PREFIX + "*");
            if (keys != null && keys.size() >= expected) {
                return keys;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return keys == null ? Set.of() : keys;
    }
}
