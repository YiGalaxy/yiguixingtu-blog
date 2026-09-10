package com.yigalaxy.yiguixingtu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.category.service.CategoryService;
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
 * 分类管理测试（AdminCategoryController / CategoryServiceImpl）
 *
 * 【本类重点盯的四件事】
 *  ① **删除限制**：分类下还有文章时不许删。不限制的后果很具体 ——
 *     文章仍然引用着那个 id，而分类已经查不到了，文章于是"没有分类名"，
 *     而且从界面上完全看不出原因（只能去数据库对照）。
 *  ② **逻辑删除 + 唯一索引那个坑**：category 有 uk_name，
 *     所以删除时必须把名字改写成 原名#deleted#id，否则"删了再建同名分类"
 *     会直接撞 Duplicate entry（而查重语句又看不见那行已删除的数据）。
 *     用例里有一条专门跑"删掉 → 再建同名"这条完整路径。
 *  ③ **手写 COUNT 必须自己过滤 deleted = 0**：@TableLogic 只管 BaseMapper
 *     生成的 SQL，管不到手写语句。所以有一条用例专门验证
 *     "文章被逻辑删除之后，分类就能删了"—— 漏掉那个条件时它会红。
 *  ④ 缓存：分类列表走 Redis（key 里带文章缓存版本号），改名/增删之后立刻失效 ——
 *     否则前台会一直显示旧的分类名，看起来像"改名没生效"。
 *
 * 【审计留痕为什么不在本类断言】
 *   审计是 AFTER_COMMIT 才落库的，而本类是 @Transactional（跑完回滚）——
 *   事务永远不提交，事件会被丢弃。所以"分类的增删改也会留痕"放在
 *   OperationLogTest 里验证（那个类用 NOT_SUPPORTED，让业务真的提交）。
 * =====================================================================
 */
class CategoryAdminTest extends AbstractIntegrationTest {

    private static final String CATEGORY_CACHE_PREFIX = RedisConfig.CACHE_CATEGORY_LIST + ":v1:";

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ArticleCacheVersion cacheVersion;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String mark;
    private User admin;
    /** 游客的 token：⑩ 用它验证写接口对 GUEST 返回 403 */
    private String guestToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mark = "K" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        admin = insertUser("test_admin_cat", "ADMIN");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        // 【为什么要真的插一个 GUEST 用户，而不是给管理员签一个 GUEST 的 token】
        //   JwtAuthenticationFilter 判断身份时以【数据库里的角色为准】，
        //   token 里的 role claim 只是签发时的快照 —— 换句话说，
        //   给 ADMIN 用户签 GUEST token，他依然按 ADMIN 处理（这正是要的行为）。
        //   这个坑在 CommentTest 里踩过一次，这里照正确的写法来。
        User guest = insertUser("test_guest_cat", "GUEST");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        clearCategoryCache();
    }

    @AfterEach
    void tearDown() {
        clearCategoryCache();
    }

    private void clearCategoryCache() {
        Set<String> keys = redis.keys(CATEGORY_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    //  一、新建与编辑
    // ================================================================

    @Test
    @DisplayName("① 新建分类 -> 落库、出现在列表里，并且让列表缓存失效")
    void createCategory_shouldPersistAndInvalidateCache() throws Exception {
        // 先读一次列表，把缓存建起来（并记下当时的版本号）
        categoryService.listAll();
        long versionBefore = Long.parseLong(cacheVersion.current());

        String name = "新分类" + mark;
        mockMvc.perform(post("/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"描述\",\"sort\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNumber());

        Category created = findByExactName(name);
        assertNotNull(created, "库里应当真的多出这个分类");
        assertEquals(3, created.getSort());
        assertEquals("描述", created.getDescription());

        // 版本号推进 = 旧缓存作废，下次读必然重新查库
        assertTrue(Long.parseLong(cacheVersion.current()) > versionBefore,
                "写操作应当推进缓存版本号");
        assertTrue(categoryService.listAll().stream().anyMatch(vo -> name.equals(vo.getName())),
                "新建的分类应当立刻出现在列表里");
    }

    @Test
    @DisplayName("② 分类名重复 -> 400；首尾空格不影响查重（『技术』与『 技术 』是同一个名字）")
    void createCategory_duplicateName_shouldBeRejected() throws Exception {
        String name = "重复分类" + mark;
        createCategory(name);

        // 带空格再建一次：Service 会 trim 后再查重，所以应当被拦住
        mockMvc.perform(post("/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   " + name + "   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已存在")));
    }

    @Test
    @DisplayName("③ 名字为空 / 超长 -> 400（校验与列长度一致，用户看得懂）")
    void createCategory_invalidName_shouldBeRejected() throws Exception {
        mockMvc.perform(post("/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        String tooLong = "长".repeat(51);
        mockMvc.perform(post("/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tooLong + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("④ 编辑分类（改名 + 改描述 + 改排序）-> 落库，且前台列表立刻显示新名字")
    void updateCategory_shouldPersistAndRefreshList() throws Exception {
        Category category = insertCategory("改前" + mark);

        categoryService.listAll();   // 建缓存

        String newName = "改后" + mark;
        mockMvc.perform(put("/admin/category/{id}", category.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + newName + "\",\"description\":\"新描述\",\"sort\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Category updated = categoryMapper.selectById(category.getId());
        assertEquals(newName, updated.getName());
        assertEquals("新描述", updated.getDescription());
        assertEquals(9, updated.getSort());

        // 【这条断言守的是"改名之后前台不能再显示旧名字"】
        //   文章列表的 VO 里带着 categoryName，那是从分类表读出来拼进去的；
        //   不推进版本号的话，前台会一直显示旧名字直到 TTL 到期，
        //   用户会以为"改名根本没生效"（而且刷新也没用）
        assertTrue(categoryService.listAll().stream().anyMatch(vo -> newName.equals(vo.getName())),
                "改名之后列表里应当是新名字");
        assertTrue(categoryService.listAll().stream().noneMatch(vo -> ("改前" + mark).equals(vo.getName())),
                "旧名字不该再出现");
    }

    @Test
    @DisplayName("⑤ 改成别人已用的名字 -> 400；保持自己原来的名字只改排序 -> 200")
    void updateCategory_duplicateName_shouldBeRejectedButSelfAllowed() throws Exception {
        Category first = insertCategory("甲" + mark);
        Category second = insertCategory("乙" + mark);

        mockMvc.perform(put("/admin/category/{id}", second.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + first.getName() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 查重时没排除自己的话，这里会报"分类名已存在"——很典型的一个 bug
        mockMvc.perform(put("/admin/category/{id}", first.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + first.getName() + "\",\"sort\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(7, categoryMapper.selectById(first.getId()).getSort());
    }

    // ================================================================
    //  二、删除（本类的重点）
    // ================================================================

    @Test
    @DisplayName("⑥ 删除没人用的分类 -> 逻辑删除，并且【把名字让出来】：同名分类能重新建出来")
    void deleteUnusedCategory_shouldReleaseName() throws Exception {
        String name = "待删分类" + mark;
        Long id = createCategory(name);

        mockMvc.perform(delete("/admin/category/{id}", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 逻辑删除：Mapper 查不到（@TableLogic 过滤了），但物理行还在
        assertNull(categoryMapper.selectById(id), "逻辑删除之后 Mapper 应当查不到");
        String rawName = jdbcTemplate.queryForObject(
                "SELECT name FROM category WHERE id = ?", String.class, id);
        assertNotNull(rawName, "逻辑删除后物理行还在");
        assertTrue(rawName.endsWith("#deleted#" + id),
                "删除时应当把名字改写成 原名#deleted#id（否则唯一索引仍占着这个名字），实际=" + rawName);

        // 【本用例的核心断言】名字被释放 → 同名分类可以重新建出来。
        //   不改名的话，这一行会撞 uk_name 报 Duplicate entry，
        //   而查重（带 @TableLogic）又认为"没有重名"—— 用户会看到一个莫名的 500
        mockMvc.perform(post("/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("⑦ 分类下还有文章 -> 拒绝删除，提示还有几篇，且分类不能被改动")
    void deleteCategoryInUse_shouldBeRejected() throws Exception {
        Category category = insertCategory("在用" + mark);
        insertArticle(category.getId());

        mockMvc.perform(delete("/admin/category/{id}", category.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1 篇")));

        // 分类必须原样保留（连同它的名字 —— 失败的删除不该留下任何痕迹）
        Category still = categoryMapper.selectById(category.getId());
        assertNotNull(still, "拒绝删除时分类必须还在");
        assertEquals(category.getName(), still.getName(), "拒绝删除时名字也不该被改写");
    }

    @Test
    @DisplayName("⑧ 文章被【逻辑删除】之后，分类就能删了（守「手写 COUNT 要自己过滤 deleted=0」）")
    void deleteCategory_shouldIgnoreSoftDeletedArticles() throws Exception {
        Category category = insertCategory("文章已删" + mark);
        Article article = insertArticle(category.getId());

        // 先把文章逻辑删除（@TableLogic：UPDATE article SET deleted = 1）
        articleMapper.deleteById(article.getId());

        // 【这条用例守的是一个很容易漏的细节】
        //   countArticlesByCategoryId 是手写 SQL，@TableLogic 管不到它 ——
        //   如果忘了写 deleted = 0，这里会告诉我"还有 1 篇文章在用"，
        //   于是这个分类永远删不掉，而用户在界面上看到的是一篇都不剩。
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT deleted FROM article WHERE id = ?", Integer.class, article.getId()),
                "前置条件：文章的物理行还在");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT deleted FROM article WHERE id = ?", Integer.class, article.getId()),
                "前置条件：文章已被逻辑删除");

        mockMvc.perform(delete("/admin/category/{id}", category.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("⑨ 删除不存在的分类 -> 404")
    void deleteCategory_notFound_shouldReturn404() throws Exception {
        mockMvc.perform(delete("/admin/category/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ================================================================
    //  三、权限与前台
    // ================================================================

    @Test
    @DisplayName("⑩ 权限：游客 403、无 token 401（写接口一个都不能漏）")
    void adminCategoryApis_shouldRequireAdmin() throws Exception {
        mockMvc.perform(get("/admin/category/list")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/category/list"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/admin/category")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"游客偷偷建的\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/admin/category/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"游客改的\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/admin/category/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("⑪ 前台分类列表仍然公开：游客能读，且能读到新建的分类")
    void publicCategoryList_shouldStayPublic() throws Exception {
        String name = "前台可见" + mark;
        createCategory(name);

        mockMvc.perform(get("/category/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 前台用的是同一个 Service 方法（同一个缓存），所以后台建的分类前台立刻能看到
        assertTrue(categoryService.listAll().stream().anyMatch(vo -> name.equals(vo.getName())));
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 走接口新建分类，返回新分类 id */
    private Long createCategory(String name) throws Exception {
        String body = mockMvc.perform(post("/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
    }

    /** 直接写库造分类（用于准备数据） */
    private Category insertCategory(String namePrefix) {
        Category category = new Category();
        category.setName(namePrefix);
        category.setDescription("测试分类");
        category.setSort(50);
        category.setDeleted(0);
        categoryMapper.insert(category);
        return category;
    }

    /** 按【精确名字】查分类（用原生 SQL：名字可能已被改写成 xxx#deleted#id，Mapper 看不见） */
    private Category findByExactName(String name) {
        return categoryMapper.selectOne(new LambdaQueryWrapper<Category>().eq(Category::getName, name));
    }

    private Article insertArticle(Long categoryId) {
        Article article = new Article();
        article.setTitle(mark + "-分类测试文章");
        article.setSummary("摘要");
        article.setContent("# 正文");
        article.setCategoryId(categoryId);
        article.setStatus(1);
        article.setIsTop(0);
        article.setViewCount(0);
        article.setAuthorId(admin.getId());
        article.setDeleted(0);
        articleMapper.insert(article);
        return article;
    }

    private User insertUser(String usernamePrefix, String role) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("分类测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }
}
