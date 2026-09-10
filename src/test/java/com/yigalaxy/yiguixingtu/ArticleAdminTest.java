package com.yigalaxy.yiguixingtu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 文章管理接口测试（AdminArticleController）
 *
 * 【前置条件】
 *   1. MySQL(3310) / Redis(6380) 已启动
 *   2. article / category 两张表已建好
 *   3. ArticleController（前台）也已经建好 —— 部分用例要交叉验证前后台
 *
 * 【为什么要用 mark 这个唯一标记？】
 *   你库里已经有手工造的真实文章了。如果断言写死 total == 2，
 *   真实数据一变测试就红 —— 这种测试比没有还糟糕（会让人不再信任它）。
 *   所以每个测试方法都生成一个独一无二的标记，标题里带上它，
 *   查询时用 keyword 把范围圈到只剩本次测试造的数据，断言才是确定的。
 *
 * 【@Transactional 自动回滚】
 *   和 UserAdminTest 一样，测试跑完自动 ROLLBACK，
 *   造的测试文章、改的状态都不会留在库里。
 * =====================================================================
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ArticleAdminTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 直接执行原生 SQL 用。
     * 用来验证"逻辑删除到底是 UPDATE 还是真的 DELETE" ——
     * 这个用 Mapper 查不出来，因为 @TableLogic 会自动把已删的行过滤掉。
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试的唯一标记 */
    private String mark;

    private User admin;
    private User guest;
    private String adminToken;
    private String guestToken;
    private Category category;

    @BeforeEach
    void setUp() {
        // 每个测试方法都会重新生成一次，保证互不干扰
        mark = "ZZT" + System.nanoTime();

        admin = insertUser("test_admin_art", "ADMIN");
        guest = insertUser("test_guest_art", "GUEST");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        category = insertCategory();
    }

    // ================================================================
    //  造数据的小工具
    // ================================================================

    private User insertUser(String username, String role) {
        User u = new User();
        u.setUsername(username + "_" + mark);   // 避免和别的测试撞用户名（有唯一索引）
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    private Category insertCategory() {
        Category c = new Category();
        c.setName(mark + "_分类");      // 分类名有唯一索引，所以也要带标记
        c.setDescription("测试用分类");
        c.setSort(99);
        c.setDeleted(0);
        categoryMapper.insert(c);
        return c;
    }

    /**
     * 直接往库里插一篇文章（绕过接口，用于准备测试数据）。
     *
     * @param name      文章名，实际标题会被拼成 mark + "_" + name
     * @param status    0草稿 1已发布
     * @param isTop     0否 1置顶
     * @param viewCount 浏览量
     */
    private Article insertArticle(String name, int status, int isTop, int viewCount) {
        Article a = new Article();
        a.setTitle(mark + "_" + name);
        a.setSummary("摘要：" + name);
        a.setContent("# " + name + "\n\n这是正文内容");
        a.setCategoryId(category.getId());
        a.setStatus(status);
        a.setIsTop(isTop);
        a.setViewCount(viewCount);
        a.setAuthorId(admin.getId());
        a.setDeleted(0);
        articleMapper.insert(a);
        return a;
    }

    /** 最常用的简写：草稿、不置顶、0 浏览 */
    private Article insertArticle(String name, int status) {
        return insertArticle(name, status, 0, 0);
    }

    /**
     * 按名字查库里的文章。
     * 注意这里查的是【真实落库的数据】，比只看接口返回可靠得多 ——
     * 接口返回可能因为代码 bug 而和库里不一致。
     */
    private Article findByTitle(String name) {
        return articleMapper.selectOne(new LambdaQueryWrapper<Article>()
                .eq(Article::getTitle, mark + "_" + name));
    }

    /** 构造"新建/编辑文章"的 JSON 请求体 */
    private String articleJson(String title, String content, Object categoryId, Object status) {
        return """
                {
                  "title": "%s",
                  "content": "%s",
                  "categoryId": %s,
                  "status": %s
                }
                """.formatted(title, content, categoryId, status);
    }

    // ================================================================
    //  一、权限验证
    // ================================================================

    @Test
    @DisplayName("① 管理员访问后台文章列表 -> 200，返回分页结构")
    void adminPage_shouldReturn200() throws Exception {
        mockMvc.perform(get("/admin/article/page")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").isNumber());
    }

    @Test
    @DisplayName("② 游客访问文章管理 -> 403 无权限")
    void guest_shouldReturn403() throws Exception {
        mockMvc.perform(get("/admin/article/page")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("③ 不带 token 访问文章管理 -> 401 未登录")
    void noToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/admin/article/page"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("④ 游客尝试新建文章 -> 403，且库里没有多出数据")
    void guest_create_shouldReturn403() throws Exception {
        long before = articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                .like(Article::getTitle, mark));

        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(mark + "_游客偷偷建的", "正文", category.getId(), 0)))
                .andExpect(status().isForbidden());

        long after = articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                .like(Article::getTitle, mark));

        // 光返回 403 不够 —— 必须确认【真的没写进库】
        assertEquals(before, after, "被拒绝的请求不应该在库里留下任何痕迹");
    }

    // ================================================================
    //  二、新建文章
    // ================================================================

    @Test
    @DisplayName("⑤ 新建草稿 -> 200，库里 status=0")
    void create_draft_shouldSaveAsDraft() throws Exception {
        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(mark + "_新建草稿", "正文内容", category.getId(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNumber());     // 返回新文章ID

        Article saved = findByTitle("新建草稿");
        assertNotNull(saved, "文章应该已经落库");
        assertEquals(0, saved.getStatus(), "新建时传 status=0，库里就该是草稿");
        assertEquals(0, saved.getViewCount(), "新文章浏览量应该从 0 开始");
        assertEquals(admin.getId(), saved.getAuthorId(), "作者应该是当前登录的管理员");
    }

    @Test
    @DisplayName("⑥ 不传 status -> 默认存成草稿（而不是意外发布）")
    void create_withoutStatus_shouldDefaultToDraft() throws Exception {
        // 故意不给 status 字段
        String body = """
                {
                  "title": "%s",
                  "content": "正文"
                }
                """.formatted(mark + "_默认状态");

        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(0, findByTitle("默认状态").getStatus(),
                "没写 status 时应该是草稿 —— 这个默认值很重要，传错就可能把半成品直接发出去");
    }

    @Test
    @DisplayName("⑦ 标题为空 -> code 400 参数校验失败")
    void create_blankTitle_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson("   ", "正文", category.getId(), 0)))
                .andExpect(status().isOk())     // 业务/校验异常仍是 HTTP 200
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("标题不能为空"));
    }

    @Test
    @DisplayName("⑧ 关联不存在的分类 -> code 404 分类不存在")
    void create_withInvalidCategory_shouldReturn404() throws Exception {
        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(mark + "_非法分类", "正文", 999999999L, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.CATEGORY_NOT_FOUND.getCode()));

        assertNull(findByTitle("非法分类"), "分类不存在时，文章不应该被创建");
    }

    @Test
    @DisplayName("⑨ 不传摘要 -> 自动从正文生成，并剥掉 Markdown 标记")
    void create_shouldAutoGenerateSummary() throws Exception {
        String body = """
                {
                  "title": "%s",
                  "content": "# 大标题\\n\\n这是**正文**内容\\n\\n```java\\nint a = 1;\\n```",
                  "categoryId": %d,
                  "status": 0
                }
                """.formatted(mark + "_自动摘要", category.getId());

        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String summary = findByTitle("自动摘要").getSummary();
        assertNotNull(summary, "摘要应该被自动生成出来");
        // 正文里出现的"内容"应该保留下来
        org.junit.jupiter.api.Assertions.assertTrue(summary.contains("这是"),
                "自动摘要里应该包含正文内容，实际是：" + summary);
        // Markdown 标记应该被剥掉
        org.junit.jupiter.api.Assertions.assertFalse(summary.contains("#"),
                "自动摘要里不该残留 Markdown 的 # 标记，实际是：" + summary);
        org.junit.jupiter.api.Assertions.assertFalse(summary.contains("*"),
                "自动摘要里不该残留 Markdown 的 ** 标记，实际是：" + summary);
        org.junit.jupiter.api.Assertions.assertFalse(summary.contains("int a = 1"),
                "代码块内容不该出现在摘要里，实际是：" + summary);
    }

    // ================================================================
    //  三、编辑文章
    // ================================================================

    @Test
    @DisplayName("⑩ 编辑文章 -> 库里标题/正文真的改了")
    void update_shouldModifyInDb() throws Exception {
        Article a = insertArticle("待编辑", 0);

        mockMvc.perform(put("/admin/article/{id}", a.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(mark + "_改过了", "改过的正文", category.getId(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Article after = articleMapper.selectById(a.getId());
        assertEquals(mark + "_改过了", after.getTitle(), "标题应该被改掉");
        assertEquals("改过的正文", after.getContent(), "正文应该被改掉");
    }

    @Test
    @DisplayName("⑪ 编辑不存在的文章 -> code 404 文章不存在")
    void update_notExist_shouldReturn404() throws Exception {
        mockMvc.perform(put("/admin/article/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(mark + "_幽灵", "正文", category.getId(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ARTICLE_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("⑫ 编辑时清空封面 -> 库里 cover 真的变成 NULL（不是被跳过）")
    void update_clearCover_shouldActuallySetNull() throws Exception {
        // 这条专门验证"为什么用 LambdaUpdateWrapper 而不是 updateById"：
        // updateById 只更新非 null 字段，传 null 会被跳过 —— 封面就删不掉。
        Article a = insertArticle("有封面的文章", 0);
        articleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Article>()
                .eq(Article::getId, a.getId())
                .set(Article::getCover, "https://example.com/cover.png"));

        assertEquals("https://example.com/cover.png",
                articleMapper.selectById(a.getId()).getCover(), "前置条件：封面先设上");

        // 编辑时【不提交 cover 字段】-> cover 为 null
        String body = """
                {
                  "title": "%s",
                  "content": "正文",
                  "categoryId": %d,
                  "status": 0
                }
                """.formatted(mark + "_有封面的文章", category.getId());

        mockMvc.perform(put("/admin/article/{id}", a.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertNull(articleMapper.selectById(a.getId()).getCover(),
                "传 null 就应该真的清成 NULL。如果这条失败，说明用了 updateById（它会跳过 null）");
    }

    // ================================================================
    //  四、发布 / 下架
    // ================================================================

    @Test
    @DisplayName("⑬ 发布草稿 -> 库里 status 变成 1")
    void publish_shouldSetStatus1() throws Exception {
        Article a = insertArticle("待发布", 0);

        mockMvc.perform(put("/admin/article/{id}/status", a.getId())
                        .param("status", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, articleMapper.selectById(a.getId()).getStatus());
    }

    @Test
    @DisplayName("⑭ 下架已发布文章 -> 库里 status 变成 0")
    void unpublish_shouldSetStatus0() throws Exception {
        Article a = insertArticle("待下架", 1);

        mockMvc.perform(put("/admin/article/{id}/status", a.getId())
                        .param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(0, articleMapper.selectById(a.getId()).getStatus());
    }

    @Test
    @DisplayName("⑮ 非法 status 值 -> code 400")
    void invalidStatus_shouldReturn400() throws Exception {
        Article a = insertArticle("非法状态", 0);

        mockMvc.perform(put("/admin/article/{id}/status", a.getId())
                        .param("status", "9")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));

        // 确认状态没被改成 9
        assertEquals(0, articleMapper.selectById(a.getId()).getStatus(), "非法值不该写进库");
    }

    @Test
    @DisplayName("⑯ 给不存在的文章改状态 -> code 404")
    void updateStatus_notExist_shouldReturn404() throws Exception {
        mockMvc.perform(put("/admin/article/{id}/status", 999999999L)
                        .param("status", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ARTICLE_NOT_FOUND.getCode()));
    }

    // ================================================================
    //  五、删除
    // ================================================================

    @Test
    @DisplayName("⑰ 删除文章 -> 逻辑删除：Mapper 查不到，但物理行还在 (deleted=1)")
    void remove_shouldBeLogicalDelete() throws Exception {
        Article a = insertArticle("待删除", 1);
        Long id = a.getId();

        mockMvc.perform(delete("/admin/article/{id}", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // ① 带 @TableLogic 的查询自动拼了 deleted=0，所以查不到
        assertNull(articleMapper.selectById(id), "删除后 Mapper 不该再查到它");

        // ② 但用原生 SQL 一查，物理行还在，只是 deleted 变成了 1
        Integer deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM article WHERE id = ?", Integer.class, id);
        assertEquals(1, deletedFlag,
                "应该是【逻辑删除】而不是物理删除 —— 物理行必须还在，否则误删就没法人工恢复了");
    }

    @Test
    @DisplayName("⑱ 删除不存在的文章 -> code 404")
    void remove_notExist_shouldReturn404() throws Exception {
        mockMvc.perform(delete("/admin/article/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ARTICLE_NOT_FOUND.getCode()));
    }

    // ================================================================
    //  六、列表筛选 / 排序
    // ================================================================

    @Test
    @DisplayName("⑲ 后台列表能看到草稿，且 status 筛选生效")
    void pageAll_shouldIncludeDrafts() throws Exception {
        insertArticle("已发布A", 1);
        insertArticle("草稿B", 0);

        // 不筛状态 -> 两条都能看到（这是后台和前台最大的区别）
        mockMvc.perform(get("/admin/article/page")
                        .param("keyword", mark)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        // 只筛草稿 -> 只剩 1 条
        mockMvc.perform(get("/admin/article/page")
                        .param("keyword", mark)
                        .param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value(mark + "_草稿B"));
    }

    @Test
    @DisplayName("⑳ 分类筛选生效")
    void page_withCategoryFilter_shouldFilter() throws Exception {
        // 另建一个分类，插一篇挂在上面的文章
        Category other = new Category();
        other.setName(mark + "_另一个分类");
        other.setSort(98);
        other.setDeleted(0);
        categoryMapper.insert(other);

        Article inThis = insertArticle("本分类文章", 1);
        Article inOther = insertArticle("别的分类文章", 1);
        articleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Article>()
                .eq(Article::getId, inOther.getId())
                .set(Article::getCategoryId, other.getId()));

        mockMvc.perform(get("/admin/article/page")
                        .param("keyword", mark)
                        .param("categoryId", String.valueOf(category.getId()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(inThis.getId().intValue()));
    }

    @Test
    @DisplayName("㉑ 默认排序：置顶的文章排最前")
    void page_defaultSort_shouldPutTopFirst() throws Exception {
        insertArticle("普通文章", 1, 0, 0);
        insertArticle("置顶文章", 1, 1, 0);

        mockMvc.perform(get("/admin/article/page")
                        .param("keyword", mark)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].title").value(mark + "_置顶文章"))
                .andExpect(jsonPath("$.data.records[0].isTop").value(1));
    }

    @Test
    @DisplayName("㉒ 按浏览量降序排序生效")
    void page_sortByViewCount_shouldWork() throws Exception {
        insertArticle("低浏览", 1, 0, 3);
        insertArticle("高浏览", 1, 0, 99);

        mockMvc.perform(get("/admin/article/page")
                        .param("keyword", mark)
                        .param("sortField", "viewCount")
                        .param("sortOrder", "desc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].title").value(mark + "_高浏览"));
    }

    @Test
    @DisplayName("㉓ 非法排序字段 -> 不报错、走默认排序，且表和数据都还在（防 SQL 注入）")
    void page_invalidSortField_shouldNotBreak() throws Exception {
        insertArticle("注入测试A", 1);
        insertArticle("注入测试B", 1);

        mockMvc.perform(get("/admin/article/page")
                        .param("keyword", mark)
                        .param("sortField", "id; DROP TABLE article")   // 典型的注入尝试
                        .param("sortOrder", "asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 关键：表还在，数据也还在。证明白名单挡住了注入。
        long count = articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                .like(Article::getTitle, mark));
        assertEquals(2L, count, "表白名单必须挡住非法排序字段，article 表和数据都要完好");
    }

    @Test
    @DisplayName("㉔ 分页参数兜底：size=-1 不崩，size=999 被压到 50")
    void page_invalidSize_shouldBeClamped() throws Exception {
        insertArticle("分页测试", 1);

        // size 传负数
        mockMvc.perform(get("/admin/article/page")
                        .param("keyword", mark)
                        .param("size", "-1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // size 传超大值 —— 必须被压到 50，否则前端传个 999999
        // 就能一次性把整张表捞出来，把数据库拖垮
        mockMvc.perform(get("/admin/article/page")
                        .param("keyword", mark)
                        .param("size", "999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(50));
    }

    @Test
    @DisplayName("㉕ 后台列表也不返回正文 content（省带宽）")
    void page_shouldNotReturnContent() throws Exception {
        insertArticle("列表不带正文", 1);

        mockMvc.perform(get("/admin/article/page")
                        .param("keyword", mark)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].content").doesNotExist());
    }

    @Test
    @DisplayName("㉖ 后台详情能拿到草稿的正文")
    void detail_draft_shouldIncludeContent() throws Exception {
        Article a = insertArticle("草稿详情", 0);

        mockMvc.perform(get("/admin/article/{id}", a.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content", containsString("这是正文内容")))
                .andExpect(jsonPath("$.data.categoryName").value(mark + "_分类"));
    }
}
