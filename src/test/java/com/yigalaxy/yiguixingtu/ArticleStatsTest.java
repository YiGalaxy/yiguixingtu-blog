package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.article.dto.ArticleStatsVO;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 站点统计接口测试（GET /article/stats）
 *
 * 【它守护的口径是"只算已发布"】
 *   首页会写着"共 N 篇文章"。如果 N 把草稿也算进去，
 *   游客点进去只数得出更少的篇数 —— 看起来像在虚报。
 *   所以"草稿不计入"必须有断言盯着，而且要看数字，不能只看接口返回 200。
 *
 * 【为什么断言"增量"而不是"总数"】
 *   库里本来就有真实文章。写死一个期望值（比如"应该有 5 篇"）会随数据变化而红，
 *   这种测试比没有还糟 —— 会让人开始不信任红灯。
 *   所以做法是：先记下当前值，造若干条数据，再断言"刚好增加了多少"。
 *
 * 【为什么每条用例都要清缓存】
 *   统计结果是带缓存的（60 秒），而 @Transactional 管不了 Redis。
 *   不清的话，上一条用例写进去的统计会被下一条读到，数字就对不上了。
 * =====================================================================
 */
class ArticleStatsTest extends AbstractIntegrationTest {

    private static final String PREFIX = RedisConfig.CACHE_ARTICLE_STATS + ":v1:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private StringRedisTemplate redis;

    /** 清掉统计缓存，让下一次查询真的走数据库 */
    private void clearStatsCache() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String uniqueMark() {
        return "ZZS" + System.nanoTime();
    }

    /** 直接插一篇文章（绕过 Service，用于精确控制 status 与 view_count） */
    private Article insertArticle(String name, int status, int viewCount) {
        Article a = new Article();
        a.setTitle(name);
        a.setSummary("摘要");
        a.setContent("正文");
        a.setStatus(status);
        a.setViewCount(viewCount);
        a.setIsTop(0);
        a.setAuthorId(1L);
        articleMapper.insert(a);
        return a;
    }

    // ================================================================
    //  一、接口本身
    // ================================================================

    @Test
    @DisplayName("① 游客就能访问（首页要显示它），且三个字段都在")
    void stats_shouldBePublicAndReturnAllFields() throws Exception {
        // 不带 token —— 首页是给所有人看的，这个接口必须匿名可访问。
        // 如果哪天有人把它从 SecurityConfig 的放行名单里删了，这条会红。
        mockMvc.perform(get("/article/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.articleCount").isNumber())
                .andExpect(jsonPath("$.data.viewCount").isNumber())
                .andExpect(jsonPath("$.data.categoryCount").isNumber());
    }

    @Test
    @DisplayName("② stats 不能被 /article/{id} 抢走（路径匹配顺序验证）")
    void statsPath_shouldNotBeTreatedAsArticleId() throws Exception {
        // /article/{id} 是"任意一段"，/article/stats 是具体字符串。
        // Spring MVC 会优先匹配更具体的那个，所以这里应该拿到统计数据，
        // 而不是走到"查 id=stats"的分支（那会 400 或者 404）。
        mockMvc.perform(get("/article/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articleCount").exists());

        // 反过来对照一下：真的传一个非数字 id，应当是"参数格式不对"。
        // 【这条断言原来写的是 500】—— 那时类型转换失败会落到兜底的
        // @ExceptionHandler(Exception.class)，报成"服务器内部错误"。
        // 现在 GlobalExceptionHandler 专门接了 MethodArgumentTypeMismatchException，
        // 返回 400「参数 id 格式不正确」。这个改动是给新接口做真实环境验证时顺手发现的
        // （用 curl 拼坏了 JSON，看到 500，于是把"客户端写错"这一类都试了一遍）。
        // 语义上 400 才对：这是调用方把请求写错了，重试一万次也不会好，
        // 而 500 会让监控把它当成服务器故障。
        mockMvc.perform(get("/article/not-a-number"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ================================================================
    //  二、口径：只算已发布，不算草稿
    // ================================================================

    @Test
    @DisplayName("③ 口径校验：已发布计入、草稿不计入")
    void stats_shouldCountPublishedOnly() {
        clearStatsCache();
        ArticleStatsVO before = articleService.stats();

        // 一次造两篇：一篇已发布（浏览量 7）、一篇草稿（浏览量 999）
        insertArticle(uniqueMark() + " 已发布", 1, 7);
        insertArticle(uniqueMark() + " 草稿", 0, 999);

        clearStatsCache();
        ArticleStatsVO after = articleService.stats();

        assertEquals(before.getArticleCount() + 1, after.getArticleCount(),
                "文章数只该 +1（草稿不算）");
        // 这条是关键：草稿那 999 次浏览绝不能被算进去。
        // 只 +7 才说明"浏览量也是按已发布文章统计的"。
        assertEquals(before.getViewCount() + 7, after.getViewCount(),
                "浏览量只该 +7 —— 草稿的 999 次不能被算进来，"
                        + "说明两条 SQL 的过滤条件都写了 status = 1");
    }

    @Test
    @DisplayName("④ 逻辑删除的文章也不计入（手写 SQL 必须自己写 deleted = 0）")
    void stats_shouldExcludeDeleted() {
        clearStatsCache();
        ArticleStatsVO before = articleService.stats();

        Article a = insertArticle(uniqueMark() + " 待删除", 1, 5);
        clearStatsCache();
        assertEquals(before.getArticleCount() + 1, articleService.stats().getArticleCount(),
                "刚插入时应当计入");

        // 逻辑删除它（deleted = 1，物理行还在）
        articleMapper.deleteById(a.getId());

        clearStatsCache();
        ArticleStatsVO after = articleService.stats();

        // 【这条用例守的是一个很容易漏的点】
        //   本项目统计用的是手写 @Select，而 @TableLogic 的逻辑删除条件
        //   只对 MyBatis-Plus 自己生成的 SQL 生效 —— 手写 SQL 必须自己写 deleted = 0。
        //   漏了的话，删掉的文章会永远被算进首页数字里，而且不会有任何报错。
        assertEquals(before.getArticleCount(), after.getArticleCount(),
                "逻辑删除的文章不该再被计入");
        assertEquals(before.getViewCount(), after.getViewCount(),
                "它的浏览量也不该再被计入");
    }

    @Test
    @DisplayName("⑤ 分类数：新增一个分类就该 +1")
    void stats_shouldCountCategories() {
        clearStatsCache();
        ArticleStatsVO before = articleService.stats();

        Category c = new Category();
        c.setName(uniqueMark() + "_统计分类");
        c.setDescription("统计接口测试用");
        c.setSort(99);
        c.setDeleted(0);
        categoryMapper.insert(c);

        clearStatsCache();
        assertEquals(before.getCategoryCount() + 1, articleService.stats().getCategoryCount(),
                "新增分类后分类数应当 +1");
    }

    @Test
    @DisplayName("⑥ 空库也不报错（SUM 返回 NULL 的情况要兜住）")
    void stats_shouldNotFailOnEmptyDatabase() {
        // 这条守的是 SQL 里的 COALESCE：一篇已发布文章都没有时，
        // SUM(view_count) 返回的是 NULL 而不是 0，直接映射到 Long 就是 null。
        // 拿 null 去做加法/展示，前端会显示 "null" 或者直接报错。
        //
        // 注意：测试库里可能有其它用例留下的数据，所以这里不断言"等于 0"，
        // 只断言"能拿到非 null 的数字"。要真正造空库场景需要清表，
        // 而清表会破坏同一个事务里的其它数据，不值当 ——
        // COALESCE 这类兜底更适合靠"代码里写了它"来保证，测试守的是"不会崩"。
        clearStatsCache();
        ArticleStatsVO stats = articleService.stats();

        assertNotNull(stats, "统计结果不该是 null");
        assertNotNull(stats.getArticleCount(), "文章数不该是 null（SUM/COUNT 都要兜底）");
        assertNotNull(stats.getViewCount(), "浏览量不该是 null（COALESCE 就是为它加的）");
        assertNotNull(stats.getCategoryCount(), "分类数不该是 null");
        assertTrue(stats.getArticleCount() >= 0);
        assertTrue(stats.getViewCount() >= 0);
    }

    // ================================================================
    //  三、缓存
    // ================================================================

    @Test
    @DisplayName("⑦ 走缓存：绕过 Service 直接插数据，第二次查还是旧数字")
    void stats_shouldBeCached() {
        clearStatsCache();
        long first = articleService.stats().getArticleCount();

        // 绕过 Service 直接插（不会推进缓存版本号）
        insertArticle(uniqueMark() + " 绕过服务插入", 1, 0);

        long second = articleService.stats().getArticleCount();
        assertEquals(first, second,
                "统计结果应当被缓存住 —— 若这里 +1 了，说明缓存没生效（又查了一次库）");
    }

    @Test
    @DisplayName("⑧ 写操作会让统计缓存失效（复用列表缓存那套版本号）")
    void write_shouldInvalidateStatsCache() {
        clearStatsCache();
        long before = articleService.stats().getArticleCount();

        // 走 Service 创建 —— 它会推进缓存版本号，
        // 而统计缓存的 key 里也带版本号，所以两个缓存会一起失效
        com.yigalaxy.yiguixingtu.article.dto.ArticleForm form =
                new com.yigalaxy.yiguixingtu.article.dto.ArticleForm();
        form.setTitle(uniqueMark() + " 走服务新建");
        form.setContent("正文");
        form.setStatus(1);
        articleService.create(form, 1L);

        assertEquals(before + 1, articleService.stats().getArticleCount(),
                "发文之后首页的统计数字应当立刻更新（版本号让统计缓存一起失效了）");
    }
}
