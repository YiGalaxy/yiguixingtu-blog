package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.article.cache.ArticleViewCounter;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.article.task.ViewCountSyncTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 浏览量异步落库的测试（ArticleViewCounter + ViewCountSyncTask）
 *
 * 【它守护的是什么】
 *   这次改动解决的是 §0.2 里的第 3 项（🔴 真缺口）：
 *   **详情页是读接口，却在每次请求里写一次库**。
 *   后果是热门文章被频繁打开时产生写放大，而且并发访问同一篇会在同一行上排队等锁。
 *
 *   现在改成"详情只写 Redis、定时批量落库"，所以本类要证明三件事：
 *     ① 访问详情【不再写库】（这正是缺口本身）
 *     ② 但用户看到的数字仍然是【包含本次访问】的正确值
 *     ③ 定时任务确实会把增量落库，而且落完不会重复累加
 *
 * 【为什么要显式设置一个很长的同步间隔】
 *   默认 5 分钟。测试类的存活时间通常只有几十秒，理论上不会撞上；
 *   但一旦撞上，定时任务会在用例中途把 Redis 的增量落库、清空，
 *   断言就会随机失败 —— 那种"偶尔红一次"的测试最消耗人。
 *   所以这里把间隔设成 1 小时（远大于用例时长），
 *   需要验证同步时就【直接调用】syncViewCounts()，结果是确定的。
 * =====================================================================
 */
@TestPropertySource(properties = {
        // 1 小时：确保测试期间定时任务不会自己触发（同步逻辑由用例直接调用）
        "app.article.view-sync-interval-ms=3600000"
})
class ArticleViewCountTest extends AbstractIntegrationTest {

    private static final String VIEW_KEY_PREFIX = "article:view:";

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private ArticleViewCounter viewCounter;

    @Autowired
    private ViewCountSyncTask syncTask;

    @Autowired
    private StringRedisTemplate redis;

    private Article article;

    @BeforeEach
    void setUp() {
        clearViewKeys();
        article = insertPublishedArticle("浏览量测试文章", 0);
    }

    @AfterEach
    void tearDown() {
        // Redis 不受 @Transactional 回滚影响，必须手动清，否则会污染别的用例
        clearViewKeys();
    }

    // ================================================================
    // 一、核心：访问详情不再写库
    // ================================================================

    @Test
    @DisplayName("① 连续上报 3 次浏览 -> 数据库的 view_count【一点没变】（这就是本来的缺口）")
    void viewReports_shouldNotWriteDatabase() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/article/{id}/view", article.getId()))
                    .andExpect(status().isOk());
        }

        // 这一条是本类最重要的断言：证明"读接口里的写操作"已经挪走了。
        // 改成 Redis 计数之前，这里会是 3
        assertEquals(0, articleMapper.selectById(article.getId()).getViewCount(),
                "上报浏览不该写数据库 —— 这正是这次改动要解决的问题");

        // 增量应该都记在 Redis 上
        assertEquals(3L, viewCounter.pending(article.getId()),
                "3 次上报应当都记在 Redis 的计数器上");
    }

    @Test
    @DisplayName("② 上报返回的数字 = 库里的快照 + 还没落库的增量（不能显示旧数字）")
    void viewReport_shouldReturnDatabaseCountPlusPendingDelta() throws Exception {
        // 先让库里的快照是 100，模拟"这篇已经被看过很多次"
        articleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Article>()
                .eq(Article::getId, article.getId())
                .set(Article::getViewCount, 100));

        // 【读详情是纯读】它只返回库里的快照，不含本次访问 ——
        // 因为"本次访问"要等页面挂载后由上报接口记，读的时候还没发生。
        // 这正是这个接口能加缓存的前提。
        mockMvc.perform(get("/article/{id}", article.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewCount").value(100));

        // 上报浏览：库 100 + 本次 1 = 101
        mockMvc.perform(post("/article/{id}/view", article.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(101));

        // 再上报一次：库 100 + 累计 2 = 102
        mockMvc.perform(post("/article/{id}/view", article.getId()))
                .andExpect(jsonPath("$.data").value(102));

        // 而库里仍然是 100 —— 说明返回的数字确实把 Redis 的增量合并进来了，
        // 只读库是算不出 101/102 的
        assertEquals(100, articleMapper.selectById(article.getId()).getViewCount());
    }

    @Test
    @DisplayName("③ 草稿/不存在的文章 -> 404，且不该产生浏览量计数")
    void nonPublishedArticle_shouldNotCountViews() throws Exception {
        Article draft = insertPublishedArticle("草稿不该被计数", 0);
        articleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Article>()
                .eq(Article::getId, draft.getId())
                .set(Article::getStatus, 0));

        mockMvc.perform(get("/article/{id}", draft.getId()))
                // 按本项目的统一约定：业务类异常返回 HTTP 200 + body.code
                //（见 README「统一返回与错误处理」；也见 ArticlePublicTest 里同样的写法）
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        // 【上报接口同样必须先校验可见性、再计数】
        //   顺序反了的话，别人拿 id 挨个探测草稿也会留下浏览痕迹 ——
        //   而"探测行为不该留痕"正是这条用例存在的理由。
        mockMvc.perform(post("/article/{id}/view", draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        assertEquals(0L, viewCounter.pending(draft.getId()),
                "访问不到的文章不该被计数（否则探测草稿的行为会留下痕迹）");
    }

    // ================================================================
    // 二、定时落库
    // ================================================================

    @Test
    @DisplayName("④ 同步任务把 Redis 的增量写进库，并把 Redis 清零（不重复累加）")
    void syncTask_shouldFlushDeltaAndClearRedis() throws Exception {
        // 【这里直接用计数器造增量，不走 HTTP】
        //   本用例关心的是"定时任务怎么把增量落进库"，不是"计数从哪个入口进来"——
        //   计数入口本身由第①②③条端到端覆盖（它们走 POST /article/{id}/view）。
        //   直接操作计数器，这个用例就不会因为入口以后再变而跟着红。
        for (int i = 0; i < 5; i++) {
            viewCounter.increment(article.getId());
        }
        assertEquals(5L, viewCounter.pending(article.getId()));

        // 直接调用同步任务（不等 5 分钟）
        syncTask.syncViewCounts();

        assertEquals(5, articleMapper.selectById(article.getId()).getViewCount(),
                "库里的浏览量应该变成 5");
        assertEquals(0L, viewCounter.pending(article.getId()),
                "落库之后 Redis 的增量必须清零 —— 不清的话下次同步会再加一遍，数字会翻倍");

        // 再同步一次：没有增量，库里不该再变（守"重复执行不会重复累加"）
        syncTask.syncViewCounts();
        assertEquals(5, articleMapper.selectById(article.getId()).getViewCount(),
                "没有新访问时重复同步，数字不应该继续涨");
    }

    @Test
    @DisplayName("⑤ 落库之后继续上报 -> 从新的基线往上加")
    void afterSync_furtherVisits_shouldContinueFromNewBase() throws Exception {
        viewCounter.increment(article.getId());

        syncTask.syncViewCounts();
        assertEquals(1, articleMapper.selectById(article.getId()).getViewCount());

        // 再累计两次：库 1 + 增量 2 = 3
        viewCounter.increment(article.getId());
        viewCounter.increment(article.getId());

        syncTask.syncViewCounts();
        assertEquals(3, articleMapper.selectById(article.getId()).getViewCount());
    }

    @Test
    @DisplayName("⑥ 没有增量时同步 -> 什么都不做，不报错")
    void syncTask_withoutDelta_shouldDoNothing() {
        // 这条看着没用，但它守的是"定时任务大部分时候都在空转"这个事实：
        // 每 5 分钟跑一次，绝大多数情况下没有增量，这条路必须安全
        syncTask.syncViewCounts();
        assertEquals(0, articleMapper.selectById(article.getId()).getViewCount());
    }

    @Test
    @DisplayName("⑦ 多篇文章的增量 -> 一次同步各自落库，不会串到别人身上")
    void syncTask_shouldFlushMultipleArticlesSeparately() throws Exception {
        Article other = insertPublishedArticle("另一篇浏览量文章", 0);

        // a 访问 2 次，other 访问 3 次
        for (int i = 0; i < 2; i++) viewCounter.increment(article.getId());
        for (int i = 0; i < 3; i++) viewCounter.increment(other.getId());

        syncTask.syncViewCounts();

        assertEquals(2, articleMapper.selectById(article.getId()).getViewCount());
        assertEquals(3, articleMapper.selectById(other.getId()).getViewCount(),
                "每篇文章的增量必须落到自己身上 —— 串了的话是很难发现的静默错误");
    }

    // ================================================================
    // 三、计数器的健壮性
    // ================================================================

    @Test
    @DisplayName("⑧ 计数器以数据库里的值为基线增量累加，而不是覆盖")
    void syncTask_shouldAddToExistingCount() throws Exception {
        // 库里已经有 50（历史数据），再访问 3 次
        articleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Article>()
                .eq(Article::getId, article.getId())
                .set(Article::getViewCount, 50));
        for (int i = 0; i < 3; i++) viewCounter.increment(article.getId());

        syncTask.syncViewCounts();

        // 必须是"加"而不是"替换成 3"。用 setSql("view_count = view_count + ?") 才是对的；
        // 写成 set(viewCount, 3) 会把历史浏览量直接抹掉
        assertEquals(53, articleMapper.selectById(article.getId()).getViewCount(),
                "落库必须是累加（50 + 3），不能覆盖历史值");
    }

    @Test
    @DisplayName("⑨ 计数器读不到时返回 0，不抛异常（Redis 抖动不该让详情页挂掉）")
    void pending_onMissingKey_shouldReturnZero() {
        assertEquals(0L, viewCounter.pending(article.getId()), "还没人访问过时增量应当是 0");
        // 访问一个不可能存在的文章 ID 也一样不该抛
        assertEquals(0L, viewCounter.pending(999_999_999L));
        assertEquals(0L, viewCounter.pending(null));
    }

    @Test
    @DisplayName("⑩ 增量取走后 Redis 里不留 key（避免垃圾累积）")
    void drainAll_shouldRemoveKeys() throws Exception {
        viewCounter.increment(article.getId());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(VIEW_KEY_PREFIX + article.getId())));

        syncTask.syncViewCounts();

        assertTrue(!Boolean.TRUE.equals(redis.hasKey(VIEW_KEY_PREFIX + article.getId())),
                "增量被取走后 key 应当消失，否则每次同步都会把它再读一遍");
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private void clearViewKeys() {
        Set<String> keys = redis.keys(VIEW_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** 插一篇已发布的文章，浏览量从 0 开始 */
    private Article insertPublishedArticle(String title, int viewCount) {
        String mark = "ZZV" + System.nanoTime();
        Article a = new Article();
        a.setTitle(title + mark);
        a.setSummary("摘要");
        a.setContent("正文");
        a.setStatus(1);
        a.setViewCount(viewCount);
        a.setIsTop(0);
        a.setAuthorId(1L);
        articleMapper.insert(a);
        return a;
    }
}
