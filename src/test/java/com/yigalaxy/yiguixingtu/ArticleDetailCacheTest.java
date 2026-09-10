package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.article.cache.ArticleViewCounter;
import com.yigalaxy.yiguixingtu.article.cache.PublishedArticleCache;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * =====================================================================
 * 文章详情缓存测试（PublishedArticleCache）
 *
 * 【详情缓存和列表缓存最关键的区别：它必须把浏览量排除在缓存之外】
 *   详情返回的浏览量 = 库里的快照 + Redis 里还没落库的增量，
 *   而那个增量每次访问都会变。要是把最终结果整个缓存起来，
 *   命中缓存的请求就不会再执行 INCR —— **浏览量永远不再增长**。
 *   这个 bug 特别隐蔽：页面照常打开、数字照常显示，只是停住不动。
 *   所以本类里有一条用例专门盯这件事（"连续访问浏览量必须持续增长"）。
 *
 * 【@Transactional 管不了 Redis，所以每条用例都要自己清缓存】
 * =====================================================================
 */
class ArticleDetailCacheTest extends AbstractIntegrationTest {

    private static final String PREFIX = RedisConfig.CACHE_ARTICLE_DETAIL + ":v1:";

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private ArticleViewCounter viewCounter;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * ArticleMapper 的【spy】：在真实 Bean 外面包一层，只为了数"它被调用了几次"。
     *
     * 【为什么需要它】防击穿的验收标准就是"并发时只查一次库"，
     *   而"查了几次库"从接口返回是看不出来的 —— 必须数真实调用。
     *
     * 【为什么用 @MockitoSpyBean 而不是 @MockBean】
     *   spy 是"包装真实对象"，方法照常执行；mock 是"替换掉"，
     *   那样就查不到数据了。这里要的是"行为不变，只多记一笔账"。
     */
    @MockitoSpyBean
    private ArticleMapper spyArticleMapper;

    private String mark;

    @AfterEach
    void cleanup() {
        clearDetailCache();
    }

    private void clearDetailCache() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private Set<String> detailKeys() {
        Set<String> keys = redis.keys(PREFIX + "*");
        return keys == null ? Set.of() : keys;
    }

    /**
     * 等缓存 key 达到期望条数。
     * 为什么不"调用完立刻数"—— 缓存写入是异步发出的，
     * 详见 ArticleCacheTest#awaitCacheKeys 的注释（那里有 MONITOR 抓到的证据）。
     */
    private Set<String> awaitDetailKeys(int expectedCount, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> keys = detailKeys();
        while (keys.size() < expectedCount && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            keys = detailKeys();
        }
        return keys;
    }

    private String uniqueMark() {
        return "ZZD" + System.nanoTime();
    }

    /** 造一篇已发布的文章 */
    private Article insertArticle(String name, int status) {
        Article a = new Article();
        a.setTitle(name);
        a.setSummary("摘要");
        a.setContent("正文 —— " + name);
        a.setStatus(status);
        a.setViewCount(0);
        a.setIsTop(0);
        a.setAuthorId(1L);
        articleMapper.insert(a);
        return a;
    }

    // ================================================================
    //  一、命中缓存
    // ================================================================

    @Test
    @DisplayName("① 第二次查同一篇 -> 走缓存（绕过 Service 改库也看不到变化）")
    void secondCall_shouldServeFromCache() {
        mark = uniqueMark();
        Article a = insertArticle(mark + " 详情缓存", 1);

        String firstTitle = articleService.getPublishedDetail(a.getId()).getTitle();
        assertEquals(mark + " 详情缓存", firstTitle);
        assertTrue(awaitDetailKeys(1, 2000).size() > 0,
                "第一次查详情之后，Redis 里应当出现详情缓存 key");

        // 绕过 Service 直接改库（不会推进缓存版本号）
        articleMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Article>()
                        .eq(Article::getId, a.getId())
                        .set(Article::getTitle, mark + " 改过的标题"));

        assertEquals(mark + " 详情缓存", articleService.getPublishedDetail(a.getId()).getTitle(),
                "第二次应当命中缓存、拿到旧标题；若拿到新标题说明缓存没生效");
    }

    @Test
    @DisplayName("② 缓存内容能正确还原（Jackson 序列化没配错，时间字段可读）")
    void cachedDetail_shouldDeserializeCorrectly() {
        mark = uniqueMark();
        Article a = insertArticle(mark + " 序列化验证", 1);
        articleService.getPublishedDetail(a.getId());

        ArticleVO fromCache = articleService.getPublishedDetail(a.getId());

        assertNotNull(fromCache.getContent(), "正文应当能从缓存里还原（详情的特点就是带正文）");
        assertEquals(mark + " 序列化验证", fromCache.getTitle());
        assertNotNull(fromCache.getCreateTime(), "createTime 应当能还原（JavaTimeModule 生效）");
    }

    // ================================================================
    //  二、【最关键的一条】浏览量不能被缓存冻住
    // ================================================================

    @Test
    @DisplayName("③ 连续访问同一篇文章 -> 浏览量必须持续增长（增量在缓存外面合并）")
    void repeatedReads_shouldKeepIncrementingViewCount() {
        mark = uniqueMark();
        Article a = insertArticle(mark + " 浏览量不能冻住", 1);

        int first = articleService.getPublishedDetail(a.getId()).getViewCount();
        int second = articleService.getPublishedDetail(a.getId()).getViewCount();
        int third = articleService.getPublishedDetail(a.getId()).getViewCount();

        assertEquals(1, first, "第一次访问应当显示 1（包含本次）");
        assertEquals(2, second,
                "第二次必须显示 2 —— 如果还是 1，说明 INCR 被缓存的命中路径跳过了，"
                        + "浏览量会永远停在那个值上（这是本类最重要的一条断言）");
        assertEquals(3, third, "第三次同理，必须继续增长");
    }

    // ================================================================
    //  三、失效：写操作后详情立刻更新
    // ================================================================

    @Test
    @DisplayName("④ 编辑文章后 -> 详情立刻是新标题（复用列表缓存的版本号）")
    void update_shouldInvalidateDetailCache() {
        mark = uniqueMark();
        ArticleForm form = new ArticleForm();
        form.setTitle(mark + " 旧标题");
        form.setContent("正文");
        form.setStatus(1);
        Long id = articleService.create(form, 1L);

        assertEquals(mark + " 旧标题", articleService.getPublishedDetail(id).getTitle());

        ArticleForm updated = new ArticleForm();
        updated.setTitle(mark + " 新标题");
        updated.setContent("正文");
        updated.setStatus(1);
        articleService.update(id, updated);

        assertEquals(mark + " 新标题", articleService.getPublishedDetail(id).getTitle(),
                "编辑之后详情应当立刻是新标题（版本号让详情缓存一起失效了）");
    }

    @Test
    @DisplayName("⑤ 下架后 -> 详情立刻 404（草稿对外当作不存在）")
    void unpublish_shouldMakeDetailNotFound() {
        mark = uniqueMark();
        ArticleForm form = new ArticleForm();
        form.setTitle(mark + " 先发布再下架");
        form.setContent("正文");
        form.setStatus(1);
        Long id = articleService.create(form, 1L);

        assertNotNull(articleService.getPublishedDetail(id));

        articleService.updateStatus(id, 0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.getPublishedDetail(id),
                "下架之后前台详情应当当作不存在");
        assertEquals(ResultCode.ARTICLE_NOT_FOUND, e.getResultCode());
    }

    @Test
    @DisplayName("⑥ 手动删掉缓存 key 之后能自愈（下次访问重新查库并写回）")
    void afterManualEvict_shouldRebuildFromDatabase() {
        mark = uniqueMark();
        Article a = insertArticle(mark + " 自愈验证", 1);

        articleService.getPublishedDetail(a.getId());
        awaitDetailKeys(1, 2000);

        clearDetailCache();
        assertEquals(0, detailKeys().size(), "手动清掉之后缓存应当为空");

        // 再访问一次：应当重新查库、并且把缓存写回来
        assertEquals(mark + " 自愈验证", articleService.getPublishedDetail(a.getId()).getTitle());
        assertTrue(awaitDetailKeys(1, 2000).size() > 0,
                "访问一次之后缓存应当被重新建起来（自愈）");
    }

    // ================================================================
    //  四、防击穿：并发打同一个冷 key，只能有一次回源
    // ================================================================

    @Test
    @DisplayName("⑦ 防击穿的开关真的开着（sync = true）")
    void stampedeProtection_shouldBeConfigured() throws Exception {
        // 【为什么要断言"配置"，而不是只断言"行为"】
        //   防击穿的效果是"并发时只查一次库"，而并发测试本身带随机性。
        //   但让它【悄悄失效】的方式却很简单：谁把 sync = true 去掉，
        //   功能看起来一切正常 —— 只是热点 key 过期时会对数据库发起一波重复查询。
        //   这种"静默降级"正是最该被钉住的，所以这里直接断言那个开关。
        //
        //   行为层面的验证在下面第⑧条（12 个线程并发，真数了一遍查库次数）。
        Cacheable cacheable = PublishedArticleCache.class
                .getMethod("load", Long.class)
                .getAnnotation(Cacheable.class);

        assertNotNull(cacheable, "PublishedArticleCache.load 上应当有 @Cacheable");
        assertTrue(cacheable.sync(),
                "必须保留 sync = true —— 去掉它防击穿就没了，而且不会有任何报错");

        // 【这里刻意不去断言"写入器的类名里有 Locking"】
        //   第一版就是这么写的，跑出来是红的：实际类名是 DefaultRedisCacheWriter。
        //   Spring Data Redis 在版本演进中调整过这块的实现与命名
        //   （旧版有独立的 LockingRedisCacheWriter），按类名断言等于把
        //   "框架内部怎么实现"焊死在测试里 —— 升级依赖就会红，而功能其实好好的。
        //   真正该证明的是"并发时只查一次库"，那件事由第⑧条直接测出来，
        //   且不依赖任何内部类名。
    }

    @Test
    @DisplayName("⑧ 12 个线程同时打同一个未缓存的详情 -> 数据库只被查 1 次（真数了一遍）")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentMisses_shouldHitDatabaseOnlyOnce() throws Exception {
        // 【为什么这条用例特意关掉测试事务（NOT_SUPPORTED）】
        //   并发要靠多个线程，而别的线程【看不到】当前测试事务里未提交的那一行 ——
        //   它们会统统查到"文章不存在"。所以这里必须真的提交。
        //   代价是本用例的数据要自己清理（见 finally）。
        mark = uniqueMark();
        Article a = insertArticle(mark + " 防击穿", 1);
        Long articleId = a.getId();

        try {
            clearDetailCache();
            clearSpy();

            int threads = 12;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();

            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            ArticleVO vo = articleService.getPublishedDetail(articleId);
                            if ((mark + " 防击穿").equals(vo.getTitle())) {
                                ok.incrementAndGet();
                            }
                        } catch (Exception ex) {
                            failed.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS),
                        "并发访问应当在 30 秒内全部完成（抢不到锁的会等锁释放，不该卡死）");
            } finally {
                pool.shutdownNow();
            }

            assertEquals(0, failed.get(), "并发访问不该有失败");
            assertEquals(threads, ok.get(), "所有并发请求都应当拿到正确结果");

            // 【核心断言】12 个线程同时遇到"缓存没有"，只允许有一次真的去查库。
            //   这就是防击穿要解决的问题：否则热点文章缓存一过期，
            //   一瞬间会有 12 条一模一样的查询打到数据库上。
            verify(spyArticleMapper, times(1)).selectById(articleId);

            // 顺带确认缓存只被建了一条（没有因为并发写重复 key）
            Set<String> keys = awaitDetailKeys(1, 2000);
            assertEquals(1, keys.size(), "同一个 id 只该有一条缓存 key，实际=" + keys);
        } finally {
            // 本用例没有测试事务兜底，必须自己清理：先删数据库行（物理删），再清缓存
            jdbcTemplate.update("DELETE FROM article WHERE id = ?", articleId);
            clearDetailCache();
        }
    }

    /** 清掉 spy 上记录的历史调用，避免被同一条用例里前面的调用干扰 */
    private void clearSpy() {
        Mockito.clearInvocations(spyArticleMapper);
    }

    // ================================================================
    //  五、TTL
    // ================================================================

    @Test
    @DisplayName("⑧ 详情缓存必须有过期时间（不能是永久的）")
    void detailCache_shouldHaveTtl() {
        mark = uniqueMark();
        Article a = insertArticle(mark + " TTL 验证", 1);
        articleService.getPublishedDetail(a.getId());

        Set<String> keys = awaitDetailKeys(1, 2000);
        assertEquals(1, keys.size(), "期望只有 1 条详情缓存 key，实际=" + keys);

        Long ttl = redis.getExpire(keys.iterator().next());
        assertNotNull(ttl, "取不到详情缓存 key 的 TTL");

        // 【这条断言守的是一个具体的风险】
        //   详情缓存用了 @Cacheable(sync = true)，而 sync 模式下 Spring 走的是
        //   Cache.get(key, Callable) 那条路 —— 那条路的实现细节里带着一个过期时间参数。
        //   如果它被传成了 Duration.ZERO，缓存就会【永不过期】，
        //   只能靠版本号失效，Redis 里的数据会无限增长。
        //   所以这里必须真的去读一次 TTL、确认它是我们配的那个区间。
        assertTrue(ttl > 0, "详情缓存必须有过期时间，不能永久，实际=" + ttl);
        assertTrue(ttl >= 300 && ttl <= 360,
                "TTL 应当在 300~360 秒之间（5 分钟 + 抖动），实际=" + ttl);
    }

    // ================================================================
    //  六、与列表缓存共用版本号
    // ================================================================

    @Test
    @DisplayName("⑨ 详情缓存的 key 里带着当前版本号（与列表缓存同一套失效机制）")
    void detailKey_shouldContainCurrentVersion() {
        mark = uniqueMark();
        Article a = insertArticle(mark + " 版本号验证", 1);
        articleService.getPublishedDetail(a.getId());

        Set<String> keys = awaitDetailKeys(1, 2000);
        assertEquals(1, keys.size(), "期望只有 1 条详情缓存 key，实际=" + keys);

        String key = keys.iterator().next();
        // 完整 key 形如 article:detail:v1:{版本号}:{文章id}
        assertTrue(key.startsWith(PREFIX) && key.endsWith(":" + a.getId()),
                "详情缓存 key 应当形如「前缀 + 版本号 + 文章id」，实际=" + key);
    }

    @Test
    @DisplayName("⑩ 不同文章各自缓存、浏览量也各自独立")
    void differentArticles_shouldHaveSeparateCacheEntries() {
        mark = uniqueMark();
        Article first = insertArticle(mark + " 第一篇", 1);
        Article second = insertArticle(mark + " 第二篇", 1);

        // 【注意每条只读一次】浏览量每次读都会 +1，
        //   所以断言"第一次读到的是 1"就必须保证在此之前没读过它。
        //   （第一版这里就是先读了 title 再断言 viewCount=1，结果拿到 2 而变红。）
        ArticleVO firstVo = articleService.getPublishedDetail(first.getId());
        ArticleVO secondVo = articleService.getPublishedDetail(second.getId());

        assertEquals(mark + " 第一篇", firstVo.getTitle(), "两篇文章应当各自取到自己的内容");
        assertEquals(mark + " 第二篇", secondVo.getTitle());
        assertEquals(1, firstVo.getViewCount(), "第一篇第一次被读，浏览量应当是 1");
        assertEquals(1, secondVo.getViewCount(), "第二篇第一次被读，浏览量也应当是 1（互不干扰）");

        Set<String> keys = awaitDetailKeys(2, 2000);
        assertEquals(2, keys.size(), "两篇文章应当各有一条缓存，实际=" + keys);

        // 再各读一次：两边的浏览量各自 +1，说明没有共用同一个计数器
        assertEquals(2, articleService.getPublishedDetail(first.getId()).getViewCount(),
                "再读一次第一篇，应当是 2（第二次读仍然走缓存，只是计数在缓存外面）");
        assertEquals(2, articleService.getPublishedDetail(second.getId()).getViewCount(),
                "再读一次第二篇，也应当是 2");
    }
}
