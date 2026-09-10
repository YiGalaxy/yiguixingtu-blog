package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.tag.dto.TagVO;
import com.yigalaxy.yiguixingtu.tag.entity.Tag;
import com.yigalaxy.yiguixingtu.tag.mapper.TagMapper;
import com.yigalaxy.yiguixingtu.tag.service.TagService;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 文章与标签关联的测试（文章打标签 + 按标签筛选）
 *
 * 【本类重点盯的五件事】
 *  ① "覆盖式"语义：编辑文章时提交的标签是【最终状态】，不是增量。
 *     用例要证明"换标签之后旧标签没了"（追加式的 bug 很隐蔽：
 *     界面上少勾一个标签，保存之后它还在）。
 *  ② 标签不存在时**整个事务回滚**：关联先被清空、校验才发现标签是假的，
 *     如果不回滚，用户的文章就会莫名其妙丢掉全部标签 ——
 *     而界面只会提示一句"有标签不存在"，用户完全想不到标签已经没了。
 *     这条守的是"事务边界"，不是某个字段。
 *  ③ 标签筛选的可见性：按标签筛出来的必须还是【已发布】，
 *     草稿不能因为"挂了标签"而泄漏出去。
 *  ④ 删文章要连带清理关联（否则关联表会永远堆着垃圾行）。
 *  ⑤ 缓存联动：打标签会推进文章缓存版本号，而标签列表的缓存 key 里带的
 *     正是那个版本号 —— 所以"打标签之后标签云的文章数立刻 +1"。
 *
 * 【怎么造数据】文章的增改走【接口】（顺带验证 ArticleForm 里 tagIds 的绑定），
 * 标签直接写库（它有自己的 TagTest 覆盖接口层）。
 * =====================================================================
 */
class ArticleTagTest extends AbstractIntegrationTest {

    /** 类内自增序号：让每个用例的标记都不同，且足够短（标签名只有 30 字符） */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private TagService tagService;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String mark;
    private User admin;
    private String adminToken;
    private Category category;

    @BeforeEach
    void setUp() {
        mark = "A" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        admin = insertUser("test_admin_arttag");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");

        category = new Category();
        category.setName(mark + "_打标签分类");
        category.setDescription("文章标签关联测试");
        category.setSort(99);
        category.setDeleted(0);
        categoryMapper.insert(category);
    }

    // ================================================================
    //  一、打标签（新建 / 编辑）
    // ================================================================

    @Test
    @DisplayName("① 新建文章时带上标签 -> 关联落库，后台详情把标签带回来")
    void createArticleWithTags_shouldPersistLinks() {
        Tag spring = insertTag("Spring");
        Tag redis = insertTag("Redis");

        Long articleId = createArticle("带标签的文章", 0, List.of(spring.getId(), redis.getId()));

        assertEquals(2, countLinks(articleId), "两个标签都应当落库");
        // 后台详情（含草稿）也要带标签：编辑界面要用它回显多选框
        ArticleVO detail = articleService.getDetail(articleId);
        assertEquals(2, detail.getTags().size(), "详情应当返回 2 个标签");
        assertTrue(detail.getTags().stream().anyMatch(t -> t.getName().equals(spring.getName())),
                "标签里应当有 Spring，实际=" + detail.getTags());
    }

    @Test
    @DisplayName("② 前台详情也返回标签；没有标签时是空数组而不是 null")
    void publishedDetail_shouldCarryTags() {
        Tag tag = insertTag("前台可见");
        Long withTag = createArticle("有标签", 1, List.of(tag.getId()));
        Long withoutTag = createArticle("没标签", 1, List.of());

        assertEquals(1, articleService.getPublishedDetail(withTag).getTags().size());
        // 空数组（不是 null）：前端可以直接 v-for，不用再写一层判空
        assertEquals(0, articleService.getPublishedDetail(withoutTag).getTags().size(),
                "没有标签时应当是空数组而不是 null");
    }

    @Test
    @DisplayName("③ 编辑文章换标签 -> 是【替换】而不是追加（旧标签必须消失）")
    void updateArticleTags_shouldReplaceNotAppend() throws Exception {
        Tag oldTag = insertTag("旧标签");
        Tag newTag = insertTag("新标签");
        Long articleId = createArticle("换标签", 0, List.of(oldTag.getId()));

        updateArticle(articleId, "换标签之后", 0, List.of(newTag.getId()));

        assertEquals(1, countLinks(articleId), "只应当剩下 1 条关联");
        ArticleVO detail = articleService.getDetail(articleId);
        assertEquals(newTag.getName(), detail.getTags().get(0).getName());
        // 这条断言才是"覆盖式"的关键：追加式的实现会在这里留下 2 个标签
        assertTrue(detail.getTags().stream().noneMatch(t -> t.getName().equals(oldTag.getName())),
                "旧标签必须被移除，实际=" + detail.getTags());
    }

    @Test
    @DisplayName("④ 编辑时不传 tagIds -> 清空标签（这是合法操作，不是漏传）")
    void updateArticleWithoutTagIds_shouldClearTags() throws Exception {
        Tag tag = insertTag("会被清掉");
        Long articleId = createArticle("清空标签", 0, List.of(tag.getId()));
        assertEquals(1, countLinks(articleId), "前置条件：先挂上一个标签");

        updateArticle(articleId, "清空标签之后", 0, null);

        assertEquals(0, countLinks(articleId), "不传 tagIds 表示清空");
        assertEquals(0, articleService.getDetail(articleId).getTags().size());
    }

    @Test
    @DisplayName("⑤ 传了不存在的标签 -> 404，且【原有标签一点没动】（校验先于写入）")
    void updateArticleWithUnknownTag_shouldNotTouchExistingTags() throws Exception {
        Tag keep = insertTag("原有标签");
        Long articleId = createArticle("校验先于写入", 0, List.of(keep.getId()));

        // 注意 categoryId 用的是【真实存在的分类】：本用例要验的是"标签不存在"，
        // 分类也传假的话，异常会先由 checkCategory 抛出，就测不到想测的那条路了
        mockMvc.perform(put("/admin/article/{id}", articleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson("校验先于写入", category.getId(), List.of(999999999L), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        // 【这条断言守的是"失败路径没有副作用"】
        //   第一版实现是"先删旧关联、再校验标签是否存在"，结果这条用例直接红了：
        //   旧关联在抛异常之前就已经被删掉。虽然方法上的 @Transactional 最终会把
        //   整个事务回滚，但那个保证依赖"事务配置不出错"。
        //
        //   ⚠️ 顺便说清一个测试层面的坑：在 @Transactional 的测试里，
        //   其实【验证不了】真正的回滚 —— 内层方法加入的是测试事务，
        //   抛异常只是把它标记成 rollback-only，删除在本次事务里仍然可见。
        //   所以与其去验证"回滚生效"，不如把实现改成"校验先于任何写入"，
        //   让"失败无副作用"成为【不依赖事务配置】的不变式，再直接断言它。
        assertEquals(1, countLinks(articleId), "校验失败不该动到原有标签");
        assertEquals(keep.getName(), articleService.getDetail(articleId).getTags().get(0).getName());
    }

    @Test
    @DisplayName("⑥ 同一个标签传两次 -> 去重，只落一条关联（联合主键的语义）")
    void duplicateTagIds_shouldBeDeduplicated() {
        Tag tag = insertTag("重复传");
        Long articleId = createArticle("去重验证", 0, List.of(tag.getId(), tag.getId(), tag.getId()));

        assertEquals(1, countLinks(articleId), "重复的 tagId 只应当产生一条关联");
    }

    // ================================================================
    //  二、按标签筛选
    // ================================================================

    @Test
    @DisplayName("⑦ 按标签筛选 -> 只返回该标签下的【已发布】文章，草稿不泄漏")
    void pageByTag_shouldOnlyReturnPublished() {
        Tag tag = insertTag("筛选标签");
        Tag otherTag = insertTag("别的标签");

        Long published = createArticle("命中-已发布", 1, List.of(tag.getId()));
        createArticle("命中-草稿", 0, List.of(tag.getId()));       // 同标签但是草稿
        createArticle("不命中-别的标签", 1, List.of(otherTag.getId()));

        ArticleQuery query = new ArticleQuery();
        query.setTagId(tag.getId());
        query.setSize(50L);

        List<ArticleVO> records = articleService.pagePublished(query).getRecords();
        assertEquals(1, records.size(), "只应当有 1 篇（草稿不算、别的标签不算）");
        assertEquals(published, records.get(0).getId());
    }

    @Test
    @DisplayName("⑧ 按标签筛选 + 关键词 -> 两个条件同时生效（AND 而不是 OR）")
    void pageByTagWithKeyword_shouldCombineConditions() {
        Tag tag = insertTag("组合筛选");
        Long hit = createArticle("组合命中", 1, List.of(tag.getId()));
        createArticle("组合不命中", 1, List.of(tag.getId()));

        ArticleQuery query = new ArticleQuery();
        query.setTagId(tag.getId());
        query.setKeyword("组合命中");
        query.setSize(50L);

        List<ArticleVO> records = articleService.pagePublished(query).getRecords();
        assertEquals(1, records.size(), "关键词也要生效，不能因为带了标签就变成 OR");
        assertEquals(hit, records.get(0).getId());
    }

    @Test
    @DisplayName("⑨ 标签下没有文章 / 标签根本不存在 -> 返回空页，而不是报错")
    void pageByTag_noResult_shouldReturnEmptyPage() {
        Tag emptyTag = insertTag("没人用");

        ArticleQuery query = new ArticleQuery();
        query.setTagId(emptyTag.getId());
        assertEquals(0, articleService.pagePublished(query).getTotal(), "空标签应当返回空页");

        // 【不存在的标签也要给空页】前端可能拿着一个已被删除的标签 id 过来
        // （用户停在旧页面上、标签刚被管理员删了）——
        // 这时报 404 只会让人看到无意义的错误，而"没有文章"才是他真正要知道的事
        ArticleQuery unknown = new ArticleQuery();
        unknown.setTagId(999999999L);
        assertEquals(0, articleService.pagePublished(unknown).getTotal(),
                "标签不存在时应当返回空页而不是抛异常");
    }

    @Test
    @DisplayName("⑩ 列表里每篇文章都带上自己的标签（整页一次查出来，不是 N+1）")
    void pageShouldCarryTagsForEveryArticle() {
        Tag first = insertTag("第一");
        Tag second = insertTag("第二");
        createArticle("列表第一篇", 1, List.of(first.getId()));
        createArticle("列表第二篇", 1, List.of(second.getId(), first.getId()));

        ArticleQuery query = new ArticleQuery();
        query.setKeyword("列表第");
        query.setSize(50L);

        List<ArticleVO> records = articleService.pagePublished(query).getRecords();
        assertEquals(2, records.size());
        for (ArticleVO vo : records) {
            assertNotNull(vo.getTags(), "每篇文章都应当带上标签字段（空数组也不能是 null）");
            assertTrue(vo.getTags().size() >= 1, "这两篇都挂了标签，实际=" + vo.getTags());
        }
    }

    // ================================================================
    //  三、删除与缓存联动
    // ================================================================

    @Test
    @DisplayName("⑪ 删除文章 -> 它的标签关联被一并清理（不能让关联表堆垃圾）")
    void deleteArticle_shouldCleanLinks() throws Exception {
        Tag tag = insertTag("删除清理");
        Long articleId = createArticle("待删文章", 0, List.of(tag.getId()));
        assertEquals(1, countLinks(articleId), "前置条件：先挂上一个标签");

        mockMvc.perform(delete("/admin/article/{id}", articleId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(0, countLinks(articleId), "文章删了，关联行也应当没了");
    }

    @Test
    @DisplayName("⑫ 给文章打标签后，前台标签列表里的文章数立刻 +1（缓存版本号推进）")
    void tagArticleCount_shouldRefreshAfterTagging() {
        Tag tag = insertTag("计数联动");

        assertEquals(0L, findTagCount(tag.getName()), "前置条件：还没人用这个标签");

        createArticle("打上标签", 1, List.of(tag.getId()));

        // 【为什么这里能立刻看到】打标签会推进文章缓存版本号，
        // 而标签列表的缓存 key 里带的正是那个版本号 —— 旧缓存瞬间失效，
        // 重新查库时文章数已经是 1。不需要为标签列表单独维护一套失效逻辑。
        assertEquals(1L, findTagCount(tag.getName()),
                "新增一篇文章并打上标签后，标签的文章数应当变成 1");
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 从【前台标签列表接口】返回的数据里读某个标签的文章数 */
    private Long findTagCount(String tagName) {
        return tagService.listPublished().stream()
                .filter(vo -> tagName.equals(vo.getName()))
                .map(TagVO::getArticleCount)
                .findFirst()
                .orElse(0L);
    }

    /** 走接口新建文章，返回新文章 id */
    private Long createArticle(String title, int status, List<Long> tagIds) {
        try {
            String body = mockMvc.perform(post("/admin/article")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(articleJson(title, category.getId(), tagIds, status)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn().getResponse().getContentAsString();
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            return root.get("data").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("新建文章失败: " + title, e);
        }
    }

    /** 走接口编辑文章 */
    private void updateArticle(Long id, String title, int status, List<Long> tagIds) throws Exception {
        mockMvc.perform(put("/admin/article/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(title, category.getId(), tagIds, status)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 构造文章的 JSON 请求体。
     *
     * 【为什么 tagIds 要区分 null 与空列表】
     *   本类有两个不同的场景要验证：
     *     · tagIds = null   → 请求体里【完全没有】这个字段（模拟前端没传）
     *     · tagIds = 空列表 → "tagIds": []（模拟前端明确提交了"不要标签"）
     *   两者在后端都会走成"清空"，但请求形态不同，测试要能表达出这个差别。
     */
    private String articleJson(String title, Long categoryId, List<Long> tagIds, int status) {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
                .append("\"title\":\"").append(mark).append("-").append(title).append("\",")
                .append("\"content\":\"# 正文\\n\\n标签测试\",")
                .append("\"categoryId\":").append(categoryId).append(",")
                .append("\"status\":").append(status);
        if (tagIds != null) {
            sb.append(",\"tagIds\":[");
            for (int i = 0; i < tagIds.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(tagIds.get(i));
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    private Tag insertTag(String name) {
        Tag tag = new Tag();
        tag.setName(name + "_" + mark);   // 标签名有唯一索引，必须带标记
        tag.setSort(1);
        tagMapper.insert(tag);
        return tag;
    }

    private User insertUser(String usernamePrefix) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("文章标签测试用户");
        u.setRole("ADMIN");
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    /** 数关联行：直接查 article_tag（那张表没有实体类，用 JdbcTemplate 最直白） */
    private int countLinks(Long articleId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_tag WHERE article_id = ?", Integer.class, articleId);
        return count == null ? 0 : count;
    }
}
