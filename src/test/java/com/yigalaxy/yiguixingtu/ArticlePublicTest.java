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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 前台公开接口测试（ArticleController / CategoryController）
 *
 * 【这个测试类守护的是整个博客最重要的一条安全底线】
 *   草稿绝不能泄漏给未登录的访客。
 *
 *   这条底线有三个口子都要堵住：
 *     ① 列表接口不能返回草稿
 *     ② 详情接口直接拿 ID 访问草稿，也要当作"不存在"
 *     ③ 前端就算自己塞一个 status=0 参数想偷看，也必须被无视
 *   下面 ③④⑤ 三条用例分别守住这三个口子。
 *
 * 【为什么这些接口不带 token 也能访问？】
 *   靠 SecurityConfig 里这段：
 *     .requestMatchers(HttpMethod.GET, "/article/page", "/article/*", "/category/list").permitAll()
 *   如果哪天有人不小心把它删了，本类的①②③会立刻变红 —— 这就是回归测试的价值。
 * =====================================================================
 */
class ArticlePublicTest extends AbstractIntegrationTest {

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

    /** 本次测试的唯一标记，用来把测试数据圈出来 */
    private String mark;

    private User admin;
    private Category category;

    @BeforeEach
    void setUp() {
        mark = "ZZT" + System.nanoTime();

        admin = new User();
        admin.setUsername("test_admin_pub_" + mark);
        admin.setPassword(passwordEncoder.encode("123456"));
        admin.setNickname("测试管理员");
        admin.setRole("ADMIN");
        admin.setStatus(1);
        admin.setDeleted(0);
        userMapper.insert(admin);

        category = new Category();
        category.setName(mark + "_分类");
        category.setDescription("测试用分类");
        category.setSort(99);
        category.setDeleted(0);
        categoryMapper.insert(category);
    }

    private Article insertArticle(String name, int status) {
        Article a = new Article();
        a.setTitle(mark + "_" + name);
        a.setSummary("摘要：" + name);
        a.setContent("# " + name + "\n\n这是正文内容");
        a.setCategoryId(category.getId());
        a.setStatus(status);
        a.setIsTop(0);
        a.setViewCount(0);
        a.setAuthorId(admin.getId());
        a.setDeleted(0);
        articleMapper.insert(a);
        return a;
    }

    // ================================================================
    //  一、公开可读（不需要登录）
    // ================================================================

    @Test
    @DisplayName("① 不带 token 读文章列表 -> 200（permitAll 生效）")
    void page_noToken_shouldReturn200() throws Exception {
        mockMvc.perform(get("/article/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("② 不带 token 读分类列表 -> 200")
    void categoryList_noToken_shouldReturn200() throws Exception {
        mockMvc.perform(get("/category/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("③ 公开接口不受影响：文章写接口依然要求登录（401）")
    void writeEndpoints_stillRequireAuth() throws Exception {
        // 交叉验证：读放行了，写绝不能跟着一起放行
        mockMvc.perform(get("/admin/article/page"))
                .andExpect(status().isUnauthorized());
    }

    // ================================================================
    //  二、【核心安全】草稿隔离
    // ================================================================

    @Test
    @DisplayName("④ 【核心】草稿不出现在前台列表，只有已发布的能看见")
    void page_shouldHideDrafts() throws Exception {
        insertArticle("已发布", 1);
        insertArticle("草稿", 0);

        mockMvc.perform(get("/article/page").param("keyword", mark))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))     // 两条里只看得见一条
                .andExpect(jsonPath("$.data.records[0].title").value(mark + "_已发布"))
                .andExpect(jsonPath("$.data.records[0].status").value(1));
    }

    @Test
    @DisplayName("⑤ 【核心】直接拿 ID 访问草稿 -> code 404，且不泄漏任何内容")
    void detail_draft_shouldReturn404() throws Exception {
        Article draft = insertArticle("秘密草稿", 0);

        mockMvc.perform(get("/article/{id}", draft.getId()))
                .andExpect(status().isOk())      // 业务异常 -> HTTP 200 + body 里的 code
                .andExpect(jsonPath("$.code").value(ResultCode.ARTICLE_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value("文章不存在"))
                // 关键：不能因为"是草稿"就回 403 ——
                // 那等于告诉对方"这里确实藏了一篇文章"，等于确认了它的存在。
                // 对未授权者来说，草稿和不存在必须长得一模一样。
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("⑥ 【核心】前端自己塞 status=0 想偷看草稿 -> 被无视，依然是已发布的那条")
    void page_withStatusParam_shouldBeIgnored() throws Exception {
        insertArticle("已发布", 1);
        insertArticle("草稿", 0);

        // 模拟攻击者手工构造请求，试图用 status 参数把草稿捞出来
        mockMvc.perform(get("/article/page")
                        .param("keyword", mark)
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value(mark + "_已发布"))
                .andExpect(jsonPath("$.data.records[0].title", not(containsString("草稿"))));
    }

    @Test
    @DisplayName("⑦ 文章被下架后，前台立刻看不到它（下架 = 对外消失）")
    void unpublishedArticle_shouldDisappearFromPublic() throws Exception {
        Article a = insertArticle("先发布再下架", 1);

        // 下架前：看得到
        mockMvc.perform(get("/article/page").param("keyword", mark))
                .andExpect(jsonPath("$.data.total").value(1));

        // 直接改库模拟"下架"
        articleMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Article>()
                        .eq(Article::getId, a.getId())
                        .set(Article::getStatus, 0));

        // 下架后：列表没了，详情也 404
        mockMvc.perform(get("/article/page").param("keyword", mark))
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/article/{id}", a.getId()))
                .andExpect(jsonPath("$.code").value(ResultCode.ARTICLE_NOT_FOUND.getCode()));
    }

    // ================================================================
    //  三、详情与浏览量
    // ================================================================

    @Test
    @DisplayName("⑧ 详情返回完整内容：正文 + 分类名")
    void detail_shouldReturnContentAndCategoryName() throws Exception {
        Article a = insertArticle("详情文章", 1);

        mockMvc.perform(get("/article/{id}", a.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value(mark + "_详情文章"))
                .andExpect(jsonPath("$.data.content", containsString("这是正文内容")))
                // categoryName 是 Service 批量查出来填上的，省得前端再发一次请求
                .andExpect(jsonPath("$.data.categoryName").value(mark + "_分类"))
                .andExpect(jsonPath("$.data.categoryId").value(category.getId().intValue()));
    }

    @Test
    @DisplayName("⑨ 列表不返回正文 content，详情才返回（省带宽）")
    void list_shouldNotReturnContent_butDetailShould() throws Exception {
        Article a = insertArticle("带宽测试", 1);

        // 列表：不该带 content
        mockMvc.perform(get("/article/page").param("keyword", mark))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].content").doesNotExist());

        // 详情：必须有 content
        mockMvc.perform(get("/article/{id}", a.getId()))
                .andExpect(jsonPath("$.data.content", containsString("这是正文内容")));
    }

    @Test
    @DisplayName("⑩ 浏览量：每访问一次详情，库里 view_count 真的 +1")
    void detail_shouldIncreaseViewCount() throws Exception {
        Article a = insertArticle("浏览量文章", 1);

        // 第一次访问
        mockMvc.perform(get("/article/{id}", a.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewCount").value(0));

        // 第二次访问（返回的是本次读取时的快照，比上一次 +1）
        mockMvc.perform(get("/article/{id}", a.getId()))
                .andExpect(jsonPath("$.data.viewCount").value(1));

        // 直接查库：两次访问，库里应该是 2
        assertEquals(2, articleMapper.selectById(a.getId()).getViewCount(),
                "访问两次，库里的 view_count 应该正好是 2（用 SQL 自增才不会并发丢计数）");
    }

    // ================================================================
    //  四、筛选 / 搜索 / 分页兜底
    // ================================================================

    @Test
    @DisplayName("⑪ 关键词能搜到标题命中，且搜不到无关文章")
    void page_keyword_shouldFilter() throws Exception {
        insertArticle("Spring安全笔记", 1);
        insertArticle("完全无关的内容", 1);

        mockMvc.perform(get("/article/page")
                        .param("keyword", mark + "_Spring"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value(mark + "_Spring安全笔记"));
    }

    @Test
    @DisplayName("⑫ 分类筛选生效")
    void page_categoryFilter_shouldWork() throws Exception {
        // 另建一个分类，把一篇已发布文章改挂过去
        Category other = new Category();
        other.setName(mark + "_另一个分类");
        other.setSort(98);
        other.setDeleted(0);
        categoryMapper.insert(other);

        Article mine = insertArticle("本分类", 1);
        Article otherArt = insertArticle("别的分类", 1);
        articleMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Article>()
                        .eq(Article::getId, otherArt.getId())
                        .set(Article::getCategoryId, other.getId()));

        mockMvc.perform(get("/article/page")
                        .param("keyword", mark)
                        .param("categoryId", String.valueOf(category.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(mine.getId().intValue()));
    }

    @Test
    @DisplayName("⑬ 分页参数兜底：size=-1 不崩，size=999 被压到 50")
    void page_invalidSize_shouldBeClamped() throws Exception {
        insertArticle("分页兜底", 1);

        mockMvc.perform(get("/article/page")
                        .param("keyword", mark)
                        .param("size", "-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/article/page")
                        .param("keyword", mark)
                        .param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(50));
    }

    @Test
    @DisplayName("⑭ 默认排序：置顶文章排在最前（首页该有的样子）")
    void page_defaultSort_shouldPutTopFirst() throws Exception {
        insertArticle("普通文章", 1);
        Article top = insertArticle("置顶文章", 1);
        articleMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Article>()
                        .eq(Article::getId, top.getId())
                        .set(Article::getIsTop, 1));

        mockMvc.perform(get("/article/page").param("keyword", mark))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].title").value(mark + "_置顶文章"))
                .andExpect(jsonPath("$.data.records[0].isTop").value(1));
    }
}