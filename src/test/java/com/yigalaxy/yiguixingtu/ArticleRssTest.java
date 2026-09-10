package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.article.dto.ArticleRssVO;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * RSS 数据接口（GET /article/rss）测试
 *
 * 【为什么后端只给数据、XML 由前端拼】站点域名、feed 标题这些只有前端知道；
 *   前端的 sitemap.xml / robots.txt 已经是同一个做法（Node 运行时路由按数据现算）。
 *   所以后端这个接口的职责就是"给出最近 20 篇已发布文章的正文"。
 *
 * 【本类重点盯的三件事】
 *  ① **只含已发布 + 按时间倒序**：订阅源里出现草稿，等于把没写完的东西推给读者。
 *  ② **带正文**：这是它与列表接口最本质的区别 —— RSS 读者的用法就是"在阅读器里读完"，
 *     只给摘要会逼他们点回站点。用例直接断言正文与原文一致。
 *  ③ **上限 20 篇**：正文是 longtext，取太多会让响应体和缓存条目膨胀到几 MB。
 *
 * 【⚠️ 本类为什么要用"未来的时间"造数据】
 *   RSS 取的是【全站最新的 20 篇】。容器里的库是所有测试类共用的，
 *   如果别的类留下了更新的文章，我造的 21 篇就可能被挤出前 20 ——
 *   那样"上限 20 篇"和"哪一篇被挤掉"就无法确定性断言。
 *   所以这里统一用 2030 年之后的时间戳造数据：它们必然比任何真实文章都新，
 *   于是"前 20 篇"完全由本用例决定。这是"让测试与库里已有数据解耦"的常用手法，
 *   和 ArticleAdminTest 里用唯一标记圈定范围是同一个目的。
 * =====================================================================
 */
class ArticleRssTest extends AbstractIntegrationTest {

    private static final String RSS_CACHE_PREFIX = RedisConfig.CACHE_ARTICLE_RSS + ":v1:";

    /** 造数据用的基准时间：2030-01-01（远晚于任何真实数据，见类注释） */
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2030, 1, 1, 0, 0);

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ArticleCacheVersion cacheVersion;

    private String mark;
    private User admin;
    private Category category;

    @BeforeEach
    void setUp() {
        mark = "RS" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        admin = insertUser();
        category = new Category();
        category.setName(mark + "_RSS分类");
        category.setDescription("RSS 测试");
        category.setSort(1);
        category.setDeleted(0);
        categoryMapper.insert(category);

        clearRssCache();
    }

    @AfterEach
    void tearDown() {
        clearRssCache();
    }

    private void clearRssCache() {
        Set<String> keys = redis.keys(RSS_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    //  一、口径与排序
    // ================================================================

    @Test
    @DisplayName("① 按时间倒序：最新的那篇排在最前面（订阅源的第一条必须是最新文章）")
    void rss_shouldOrderByTimeDesc() {
        insertArticle("较早的一篇", 1, BASE_TIME.plusMinutes(1));
        insertArticle("最新的一篇", 1, BASE_TIME.plusMinutes(2));

        List<ArticleRssVO> items = articleService.rssItems();

        assertTrue(items.size() >= 2, "至少应当有本用例造的两篇");
        // 用 future 时间造数据，所以前两条必然是本用例的
        assertTrue(items.get(0).getTitle().endsWith("最新的一篇"),
                "第一条应当是最新的，实际=" + items.get(0).getTitle());
        assertTrue(items.get(1).getTitle().endsWith("较早的一篇"),
                "第二条应当是较早的，实际=" + items.get(1).getTitle());
    }

    @Test
    @DisplayName("② 只含已发布：草稿不该出现在订阅源里")
    void rss_shouldOnlyContainPublished() {
        insertArticle("已发布", 1, BASE_TIME.plusMinutes(1));
        insertArticle("草稿", 0, BASE_TIME.plusMinutes(2));

        List<ArticleRssVO> items = articleService.rssItems();

        // 草稿的时间更新，如果它被算进来必然会排在第一 —— 这条断言正好能抓住它
        assertTrue(items.stream().noneMatch(i -> i.getTitle().endsWith("草稿")),
                "订阅源里不该有草稿，实际=" + items.stream().map(ArticleRssVO::getTitle).toList());
    }

    @Test
    @DisplayName("③ 带正文（这是它与列表接口最本质的区别：RSS 读者要在阅读器里读完）")
    void rss_shouldCarryFullContent() {
        String content = "# 标题\n\n这是正文的第一段。\n\n第二段里还有一些 `code`。";
        Article article = insertArticle("带正文的一篇", 1, BASE_TIME.plusMinutes(1));
        // 上面插入时用的是统一正文，这里改成这个用例专用的长正文再取
        article.setContent(content);
        articleMapper.updateById(article);

        List<ArticleRssVO> items = articleService.rssItems();
        ArticleRssVO first = items.stream()
                .filter(i -> i.getTitle().endsWith("带正文的一篇"))
                .findFirst()
                .orElse(null);

        assertNotNull(first, "应当能找到本用例的文章");
        // 列表接口（ArticleVO 在分页里 content 恒为 null）刻意不带正文，
        // 而这里必须带 —— 这条断言就在守这个区别
        assertEquals(content, first.getContent(), "正文必须原样带出");
        assertNotNull(first.getCreateTime(), "RSS 的 pubDate 需要它");
    }

    @Test
    @DisplayName("④ 上限 20 篇：造 21 篇只回 20 篇，且被挤掉的是最早那篇")
    void rss_shouldCapAtTwenty() {
        // 21 篇，时间依次递增（第 0 篇最早、第 20 篇最新）
        for (int i = 0; i <= 20; i++) {
            insertArticle(String.format("第%02d篇", i), 1, BASE_TIME.plusMinutes(i));
        }

        List<ArticleRssVO> items = articleService.rssItems();

        assertEquals(20, items.size(), "上限必须是 20 篇（正文是 longtext，给太多会让响应体膨胀）");
        // 被挤掉的应当是【最早】的那一篇，而不是随便哪一篇
        assertTrue(items.stream().anyMatch(i -> i.getTitle().endsWith("第20篇")),
                "最新的必须在里面");
        assertFalse(items.stream().anyMatch(i -> i.getTitle().endsWith("第00篇")),
                "最早的那篇应当被上限挤掉");
    }

    // ================================================================
    //  二、权限与缓存
    // ================================================================

    @Test
    @DisplayName("⑤ 游客就能访问（订阅源要能被任何阅读器直接拉）")
    void rss_shouldBePublic() throws Exception {
        mockMvc.perform(get("/article/rss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("⑥ 走缓存：绕过 Service 直插文章看不到，写文章后立刻能看到")
    void rss_shouldBeCached() {
        insertArticle("缓存前", 1, BASE_TIME.plusMinutes(1));
        int before = articleService.rssItems().size();
        assertTrue(awaitRssCacheKeys(1, 2000).size() > 0,
                "第一次调用之后 Redis 里应当出现 article:rss 的缓存 key");

        // 绕过 Service 直插（Mapper 上没有缓存注解，所以缓存不会失效）
        insertArticle("绕过Service插的", 1, BASE_TIME.plusMinutes(2));
        assertEquals(before, articleService.rssItems().size(),
                "这次应当直接吃缓存，看不到绕过 Service 插进去的文章");

        // 推进版本号（等价于"有人正常写了文章"）→ key 变了 → 重新查库
        cacheVersion.bump();
        assertTrue(articleService.rssItems().size() > before,
                "版本号推进之后应当重新查库，能看到新文章");
    }

    @Test
    @DisplayName("⑦ 缓存 key 带版本号且有 TTL（订阅源不会变成永远不更新的数据）")
    void rssCacheKey_shouldCarryVersionAndTtl() {
        articleService.rssItems();

        Set<String> keys = awaitRssCacheKeys(1, 2000);
        assertEquals(1, keys.size(), "RSS 只有一份，应当只有一条缓存 key，实际=" + keys);
        String key = keys.iterator().next();
        assertTrue(key.contains(String.valueOf(cacheVersion.current())),
                "缓存 key 里应当带当前版本号，实际=" + key);

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "缓存必须有 TTL 兜底，实际剩余秒数=" + ttl);
    }

    // ================================================================
    //  工具方法
    // ================================================================

    private Article insertArticle(String name, int status, LocalDateTime createTime) {
        Article article = new Article();
        article.setTitle(mark + "-" + name);
        article.setSummary("RSS 测试摘要");
        article.setContent("# RSS 测试\n\n正文内容");
        article.setCategoryId(category.getId());
        article.setStatus(status);
        article.setIsTop(0);
        article.setViewCount(0);
        article.setAuthorId(admin.getId());
        article.setDeleted(0);
        article.setCreateTime(createTime);
        articleMapper.insert(article);
        return article;
    }

    private User insertUser() {
        User u = new User();
        u.setUsername("test_rss_user_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("RSS 测试用户");
        u.setRole("ADMIN");
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    private Set<String> awaitRssCacheKeys(int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> keys = Set.of();
        while (System.currentTimeMillis() < deadline) {
            keys = redis.keys(RSS_CACHE_PREFIX + "*");
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
