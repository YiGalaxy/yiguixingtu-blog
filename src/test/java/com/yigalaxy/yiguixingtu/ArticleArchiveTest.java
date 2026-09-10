package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.article.dto.ArticleArchiveVO;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
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
 * 归档（GET /article/archive）测试
 *
 * 【这个接口解决什么】"我想按时间翻一翻旧文章" —— 分页列表只能一篇篇翻，
 * 而归档页要的是一眼看到"哪年哪月写过几篇、都是什么"。
 *
 * 【本类重点盯的四件事】
 *  ① **口径**：只包含【已发布】。归档是给访客看的导航，
 *     草稿混进去等于把没写完的东西公示了（和列表、统计同一个口径）。
 *  ② **分组与排序**：按"年-月"分组、月份倒序（最新的在最前）、月内文章也倒序。
 *     这条最容易写错：分组逻辑一旦用了 Map 而没排序，月份顺序就会随机跳。
 *  ③ **空数据**：一篇都没有时返回空分组 + total=0，而不是 null 或者报错。
 *  ④ **缓存**：它走 Redis（key 是文章缓存版本号），
 *     所以"绕过 Service 直插文章"看不到新内容，而文章写操作之后立刻能看到。
 *
 * 【为什么不用 MockMvc 提交数据】归档是只读接口，数据直接用 Mapper 造最快；
 * 只有"匿名能不能访问"那一条需要用 MockMvc（验证 SecurityConfig 的放行）。
 * =====================================================================
 */
class ArticleArchiveTest extends AbstractIntegrationTest {

    private static final String ARCHIVE_CACHE_PREFIX = RedisConfig.CACHE_ARTICLE_ARCHIVE + ":v1:";

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
        mark = "AR" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        admin = insertUser();
        category = new Category();
        category.setName(mark + "_归档分类");
        category.setDescription("归档测试");
        category.setSort(1);
        category.setDeleted(0);
        categoryMapper.insert(category);

        clearArchiveCache();
    }

    @AfterEach
    void tearDown() {
        clearArchiveCache();
    }

    private void clearArchiveCache() {
        Set<String> keys = redis.keys(ARCHIVE_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    //  一、口径与分组
    // ================================================================

    @Test
    @DisplayName("① 只包含已发布：草稿不出现在归档里")
    void archive_shouldOnlyContainPublished() {
        insertArticle("发布的文章", 1, LocalDateTime.of(2026, 9, 1, 10, 0));
        insertArticle("草稿文章", 0, LocalDateTime.of(2026, 9, 2, 10, 0));

        ArticleArchiveVO archive = archiveOnlyMine();

        assertEquals(1L, archive.getTotal(), "只应当有 1 篇（草稿不算）");
        assertEquals(1, archive.getMonths().size());
        assertTrue(archive.getMonths().get(0).getArticles().get(0).getTitle().endsWith("发布的文章"),
                "归档里应当是那篇已发布的");
    }

    @Test
    @DisplayName("② 按年月分组：同月的在一组，跨月的分成两组")
    void archive_shouldGroupByYearAndMonth() {
        insertArticle("九月第一篇", 1, LocalDateTime.of(2026, 9, 1, 10, 0));
        insertArticle("九月第二篇", 1, LocalDateTime.of(2026, 9, 20, 10, 0));
        insertArticle("八月那篇", 1, LocalDateTime.of(2026, 8, 15, 10, 0));

        ArticleArchiveVO archive = archiveOnlyMine();

        assertEquals(3L, archive.getTotal());
        assertEquals(2, archive.getMonths().size(), "九月一组、八月一组");

        // 月份倒序：最新的月份在最前面
        assertEquals(9, archive.getMonths().get(0).getMonth(), "九月应当排在前面");
        assertEquals(8, archive.getMonths().get(1).getMonth());

        // 每组的 count 必须和组内文章数一致（前端要用 count 做展开/收起）
        for (ArticleArchiveVO.ArchiveMonth month : archive.getMonths()) {
            assertEquals(month.getArticles().size(), month.getCount(),
                    "count 应当等于该月文章数");
            assertEquals(2026, month.getYear());
        }
        assertEquals(2, archive.getMonths().get(0).getCount(), "九月 2 篇");
        assertEquals(1, archive.getMonths().get(1).getCount(), "八月 1 篇");
    }

    @Test
    @DisplayName("③ 月内的文章也是倒序（最新写的在最上面）")
    void archive_shouldOrderArticlesInsideMonthDesc() {
        insertArticle("早写的", 1, LocalDateTime.of(2026, 9, 1, 10, 0));
        insertArticle("晚写的", 1, LocalDateTime.of(2026, 9, 25, 10, 0));

        ArticleArchiveVO archive = archiveOnlyMine();
        ArticleArchiveVO.ArchiveMonth september = archive.getMonths().get(0);

        // 【为什么这条值得单独测】分组很容易写成"用 Map 装完再输出"，
        // 那样月份顺序会随机、月内顺序也会丢 —— 而 SQL 已经按时间倒序取出来了，
        // 这里断言的是"这个顺序被保留下来了"
        assertTrue(september.getArticles().get(0).getTitle().endsWith("晚写的"),
                "月内应当按时间倒序，实际第一篇=" + september.getArticles().get(0).getTitle());
        assertTrue(september.getArticles().get(1).getTitle().endsWith("早写的"));
        assertTrue(september.getArticles().get(0).getCreateTime()
                        .isAfter(september.getArticles().get(1).getCreateTime()),
                "第一篇的时间应当更晚");
    }

    @Test
    @DisplayName("④ 一篇都没有时：返回空分组 + total=0（不是 null、也不报错）")
    void archive_withNoData_shouldReturnEmpty() {
        // 本类在 @BeforeEach 里没有造任何文章，所以这里天然是"空"的场景
        ArticleArchiveVO archive = articleService.archive();

        assertNotNull(archive, "应当返回对象而不是 null");
        assertNotNull(archive.getMonths(), "months 应当是空列表而不是 null");
        assertEquals(0L, archive.getTotal());
        // 注意：这里不能断言 months 是整个库空的（开发库里可能有别的文章），
        // 所以断言的是"本用例造的数据不存在"，而不是"归档为空"。
        // 这也正是本类所有断言都先按 mark 圈定范围的原因。
        assertFalse(archive.getMonths().stream()
                        .flatMap(m -> m.getArticles().stream())
                        .anyMatch(a -> a.getTitle().startsWith(mark)),
                "本用例没造文章，归档里不该有带本用例标记的内容");
    }

    // ================================================================
    //  二、权限与缓存
    // ================================================================

    @Test
    @DisplayName("⑤ 游客就能访问（归档页是给访客看的导航）")
    void archive_shouldBePublic() throws Exception {
        mockMvc.perform(get("/article/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.months").isArray())
                .andExpect(jsonPath("$.data.total").isNumber());
    }

    @Test
    @DisplayName("⑥ 走缓存：绕过 Service 直插文章看不到，写操作推进版本号后立刻能看到")
    void archive_shouldBeCached() {
        insertArticle("缓存前的文章", 1, LocalDateTime.of(2026, 9, 1, 10, 0));
        long before = articleService.archive().getTotal();
        assertTrue(awaitArchiveCacheKeys(1, 2000).size() > 0,
                "第一次调用之后 Redis 里应当出现 article:archive 的缓存 key");

        // 绕过 Service 直插一篇（Mapper 上没有缓存注解，所以缓存不会失效）
        insertArticle("绕过Service插的", 1, LocalDateTime.of(2026, 9, 2, 10, 0));
        assertEquals(before, articleService.archive().getTotal(),
                "这次应当直接吃缓存，看不到绕过 Service 插进去的文章");

        // 推进版本号（等价于"有人正常写了文章"）→ key 变了 → 必然未命中 → 重新查库
        cacheVersion.bump();
        assertTrue(articleService.archive().getTotal() > before,
                "版本号推进之后应当重新查库，能看到新文章");
    }

    @Test
    @DisplayName("⑦ 缓存 key 里带版本号，且有 TTL（不会变成永不过期的数据）")
    void archiveCacheKey_shouldCarryVersionAndTtl() {
        articleService.archive();

        Set<String> keys = awaitArchiveCacheKeys(1, 2000);
        assertEquals(1, keys.size(), "归档只有一份，应当只有一条缓存 key，实际=" + keys);
        String key = keys.iterator().next();
        assertTrue(key.contains(String.valueOf(cacheVersion.current())),
                "缓存 key 里应当带当前版本号（这是「写文章立刻失效」的全部原理），实际=" + key);

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "缓存必须有 TTL 兜底，实际剩余秒数=" + ttl);
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /**
     * 只取"本用例造的数据"构成的归档。
     *
     * 【为什么不能直接断言 articleService.archive() 】容器里的库是所有测试类共用的，
     * 开发库里还可能有真实文章。所以断言前要按 mark 过滤 ——
     * 这与 ArticleAdminTest 里"用唯一标记圈定范围"是同一条纪律。
     */
    private ArticleArchiveVO archiveOnlyMine() {
        ArticleArchiveVO all = articleService.archive();
        ArticleArchiveVO mine = new ArticleArchiveVO();
        long total = 0;
        for (ArticleArchiveVO.ArchiveMonth month : all.getMonths()) {
            ArticleArchiveVO.ArchiveMonth copy = new ArticleArchiveVO.ArchiveMonth();
            copy.setYear(month.getYear());
            copy.setMonth(month.getMonth());
            for (ArticleArchiveVO.ArchiveArticle article : month.getArticles()) {
                if (article.getTitle() != null && article.getTitle().startsWith(mark)) {
                    copy.getArticles().add(article);
                }
            }
            if (!copy.getArticles().isEmpty()) {
                copy.setCount(copy.getArticles().size());
                mine.getMonths().add(copy);
                total += copy.getArticles().size();
            }
        }
        mine.setTotal(total);
        return mine;
    }

    private Article insertArticle(String name, int status, LocalDateTime createTime) {
        Article article = new Article();
        article.setTitle(mark + "-" + name);
        article.setSummary("归档测试摘要");
        article.setContent("# 归档测试\n\n正文");
        article.setCategoryId(category.getId());
        article.setStatus(status);
        article.setIsTop(0);
        article.setViewCount(0);
        article.setAuthorId(admin.getId());
        article.setDeleted(0);
        // 【为什么要显式写 create_time】归档是按它分组的，而默认值是"当前时间"——
        // 不显式指定的话所有文章都会落在同一个月里，分组的用例就测不出东西
        article.setCreateTime(createTime);
        articleMapper.insert(article);
        return article;
    }

    private User insertUser() {
        User u = new User();
        u.setUsername("test_archive_user_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("归档测试用户");
        u.setRole("ADMIN");
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    /** 等缓存 key 出现（Spring Data Redis 的写入是异步发出的，直接断言会偶发变红） */
    private Set<String> awaitArchiveCacheKeys(int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> keys = Set.of();
        while (System.currentTimeMillis() < deadline) {
            keys = redis.keys(ARCHIVE_CACHE_PREFIX + "*");
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
