package com.yigalaxy.yiguixingtu;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * =====================================================================
 * 文章列表缓存测试（ArticleServiceImpl.pagePublished + RedisConfig）
 *
 * 【为什么要专门测缓存，而不是"跑通了就行"】
 *   缓存的失败方式很特殊：它【不会报错】。
 *   序列化配错、key 拼错、注解没生效、清缓存漏了一个写接口……
 *   表现全都是"接口正常返回，只是数据不对/旧了"。
 *   所以缓存必须靠断言来验证，靠肉眼看日志是看不出来的。
 *
 * 【本类的验证思路：不数 SQL，而是"制造一个只有缓存生效才会出现的结果"】
 *   想知道"第二次请求有没有打数据库"，最直接的想法是数 SQL 执行次数，
 *   但那要额外装一个统计插件，成本高、也不够直观。
 *   这里用一个更巧、也更贴近业务的办法：
 *     ① 先调一次接口 → 结果进缓存
 *     ② 绕过 Service（直接调 Mapper）插一篇新文章
 *        ⚠️ 这一步是关键：Mapper 上没有 @CacheEvict，所以缓存不会被清
 *     ③ 再调一次同样的接口
 *        · 如果缓存生效 → 返回的还是 ① 的结果（看不到新文章）
 *        · 如果缓存没生效 → 会重新查库，能看到新文章
 *   同样是"读接口"，用 Service 写就会清缓存、用 Mapper 写就不会 ——
 *   这个差别正好把"清缓存"和"走缓存"两件事分开验证了。
 *
 * 【@Transactional 管不了 Redis，这是本类必须手动清 key 的原因】
 *   基类上的 @Transactional 能让数据库改动在用例结束时报废回滚，
 *   但 Redis 不在事务里 —— 缓存会真的留下来，污染下一个用例。
 *   所以 @BeforeEach / @AfterEach 都要清一次缓存。
 *
 * 【用到的东西】
 *   · {@code @Cacheable} / {@code @CacheEvict} —— Spring Cache 的注解，
 *     靠 AOP 代理生效；调用必须从外部进入 Service（详情见 ArticleServiceImpl 类注释）
 *   · {@link StringRedisTemplate} —— Spring Boot 自动配好的 Redis 客户端。
 *     这里只用来【查 TTL 和清 key】，业务代码不该直接用它读写缓存
 *   · {@code redis.keys(pattern)} —— 按前缀找出缓存 key。
 *     ⚠️ KEYS 命令在生产环境是危险操作（会阻塞 Redis），所以在测试里用没问题；
 *     生产代码里绝不能用它，这也是本项目没把清缓存写成 KEYS 匹配的原因
 *   · {@code opsForValue().getOperations().getExpire(key)} —— 取某个 key 剩余的存活秒数
 * =====================================================================
 */
class ArticleCacheTest extends AbstractIntegrationTest {

    /** 缓存 key 在 Redis 里的完整前缀（与 RedisConfig 里 computePrefixWith 的规则一致） */
    private static final String KEY_PREFIX = RedisConfig.CACHE_ARTICLE_PAGE + ":v1:";

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * 缓存版本号组件。
     * 用例里用它断言两件事：写操作确实推进了版本号、缓存 key 里带的就是当前版本号。
     */
    @Autowired
    private ArticleCacheVersion cacheVersion;

    @BeforeEach
    @AfterEach
    void clearCache() {
        // 上一个用例（或别的测试类）留下的缓存必须清掉，否则本类的判断全会被污染
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    // 一、读：第二次请求走缓存，不再查库
    // ================================================================

    @Test
    @DisplayName("第二次查同样的列表 -> 走缓存，看不到「绕过 Service 新插的数据」")
    void pagePublished_secondCall_shouldServeFromCache() {
        String mark = uniqueMark();
        insertArticleDirectly(mark + " 第一篇文章");

        ArticleQuery query = queryByKeyword(mark);

        // ① 第一次调用：缓存未命中 → 查库 → 结果写进缓存
        long firstTotal = articleService.pagePublished(query).getTotal();
        assertEquals(1L, firstTotal, "第一次应当查到 1 篇");
        assertTrue(awaitCacheKeys(1, 2000).size() > 0,
                "第一次调用之后，Redis 里应当出现缓存 key（见下面 awaitCacheKeys 的说明）");

        // ② 绕过 Service，直接往库里再插一篇（Mapper 上没有清缓存，缓存不会被清）
        insertArticleDirectly(mark + " 第二篇文章");

        // ③ 再查一次：如果走缓存，拿到的应该还是"只有 1 篇"的旧结果
        long secondTotal = articleService.pagePublished(query).getTotal();

        assertEquals(1L, secondTotal,
                "第二次应当命中缓存、返回第一次的结果；"
                        + "若返回 2，说明缓存没生效（又查了一次库）");
    }

    @Test
    @DisplayName("缓存内容不是 null，且能正确还原成 ArticleVO（序列化没配错）")
    void pagePublished_cachedValue_shouldDeserializeBackToVo() {
        String mark = uniqueMark();
        insertArticleDirectly(mark + " 序列化验证");

        ArticleQuery query = queryByKeyword(mark);
        articleService.pagePublished(query);          // 写缓存

        // 从缓存里再取一次。这一条专门盯 Jackson 序列化：
        // 如果没注册 JavaTimeModule，写缓存时就会因为 LocalDateTime 报错；
        // 如果类型信息丢了，读出来会是 LinkedHashMap 而不是 ArticleVO，
        // 下面访问 getTitle() 就会 ClassCastException
        IPage<ArticleVO> fromCache = articleService.pagePublished(query);

        assertNotNull(fromCache.getRecords(), "从缓存读出来的分页结果不该是 null");
        assertEquals(1, fromCache.getRecords().size());
        ArticleVO vo = fromCache.getRecords().get(0);
        assertEquals(mark + " 序列化验证", vo.getTitle(), "从缓存还原出来的标题应当与写入时一致");
        // 时间字段能读出来，说明 JavaTimeModule 确实生效了
        assertNotNull(vo.getCreateTime(), "createTime 应当能从缓存里正确还原");
    }

    // ================================================================
    // 二、写：四个写操作都要把列表缓存清掉
    // ================================================================

    @Test
    @DisplayName("新增文章 -> 清掉列表缓存，前台立刻能看到")
    void create_shouldEvictListCache() {
        String mark = uniqueMark();
        ArticleQuery query = queryByKeyword(mark);

        // 先查一次，把"0 篇"这个结果缓存起来
        assertEquals(0L, articleService.pagePublished(query).getTotal());

        // 通过 Service 新增（带 @CacheEvict）
        articleService.create(formOf(mark + " 新文章", 1), 1L);

        // 缓存被清了，所以这次会重新查库、看到新文章
        assertEquals(1L, articleService.pagePublished(query).getTotal(),
                "新增文章后，前台列表应当立刻能看到它（缓存已被清除）");
    }

    @Test
    @DisplayName("把草稿改成已发布 -> 清掉列表缓存，前台立刻能看到")
    void updateStatus_shouldEvictListCache() {
        String mark = uniqueMark();

        // 先造一篇草稿（status=0，前台看不到）
        Long draftId = articleService.create(formOf(mark + " 草稿", 0), 1L);
        ArticleQuery query = queryByKeyword(mark);
        assertEquals(0L, articleService.pagePublished(query).getTotal(), "草稿不该出现在前台");

        // 发布它
        articleService.updateStatus(draftId, 1);

        assertEquals(1L, articleService.pagePublished(query).getTotal(),
                "发布之后前台应当立刻能看到");
    }

    @Test
    @DisplayName("删除文章 -> 清掉列表缓存，前台立刻消失")
    void remove_shouldEvictListCache() {
        String mark = uniqueMark();
        Long id = articleService.create(formOf(mark + " 待删除", 1), 1L);
        ArticleQuery query = queryByKeyword(mark);
        assertEquals(1L, articleService.pagePublished(query).getTotal());

        articleService.remove(id);

        assertEquals(0L, articleService.pagePublished(query).getTotal(),
                "删除之后前台应当立刻看不到");
    }

    @Test
    @DisplayName("编辑文章标题 -> 清掉列表缓存，前台立刻是新标题")
    void update_shouldEvictListCache() {
        String mark = uniqueMark();
        Long id = articleService.create(formOf(mark + " 旧标题", 1), 1L);
        ArticleQuery query = queryByKeyword(mark);
        assertEquals(mark + " 旧标题", articleService.pagePublished(query).getRecords().get(0).getTitle());

        articleService.update(id, formOf(mark + " 新标题", 1));

        assertEquals(mark + " 新标题", articleService.pagePublished(query).getRecords().get(0).getTitle(),
                "编辑之后缓存应被清除，前台拿到的是新标题");
    }

    // ================================================================
    // 三、TTL：正常内容与空结果要给不同的时长（防穿透 + 防雪崩）
    // ================================================================

    @Test
    @DisplayName("有内容的缓存 -> TTL 落在 [5分钟, 6分钟] 区间（基础时长 + 随机抖动）")
    void cachedResult_ttlShouldBeWithinExpectedRange() {
        String mark = uniqueMark();
        insertArticleDirectly(mark + " TTL 验证");
        articleService.pagePublished(queryByKeyword(mark));

        long ttlSeconds = ttlOfTheOnlyCacheKey();

        // 基础 5 分钟 = 300 秒，抖动 0~60 秒
        assertTrue(ttlSeconds > 0,
                "缓存必须有过期时间，不能是永久的，实际=" + ttlSeconds);
        assertTrue(ttlSeconds >= 300 && ttlSeconds <= 360,
                "TTL 应当在 300~360 秒之间（5 分钟 + 0~60 秒抖动），实际=" + ttlSeconds);
    }

    @Test
    @DisplayName("空结果（防穿透）-> 也会被缓存，但 TTL 只有 30 秒左右")
    void emptyResult_shouldBeCachedWithShortTtl() {
        // 用一个查不到结果的关键词：库里的文章标题不会包含这个随机串
        String mark = uniqueMark();

        assertEquals(0L, articleService.pagePublished(queryByKeyword(mark)).getTotal(),
                "这个关键词本来就查不到东西 —— 前提成立，才谈得上验证防穿透");

        // 【这条是防穿透的核心断言】空结果必须也被写进缓存。
        //   不写的话，别人拿一堆必然查不到的条件反复刷接口，
        //   每次都会落到数据库，缓存等于被绕过去了。
        //
        // ⚠️ 早先这里记录的结论是"空结果根本不会被缓存"，那是被 put 的异步性骗了：
        //    MONITOR 里能清楚看到空结果被正常 SET 进去。详见 awaitCacheKeys 的注释。
        Set<String> keys = awaitCacheKeys(1, 2000);
        assertEquals(1, keys.size(), "空结果也应当被缓存，实际 key=" + keys);

        long ttlSeconds = ttlOfTheOnlyCacheKey();
        assertTrue(ttlSeconds > 0, "空结果的缓存也要有过期时间，实际=" + ttlSeconds);
        assertTrue(ttlSeconds <= 40,
                "空结果的 TTL 应当明显短于正常内容（30 秒左右），实际=" + ttlSeconds);
    }

    // ================================================================
    // 【这一版没做的一件事，连同原因记在这里，别让后来人以为漏了】
    //
    // TTL 抖动"是否真的每次都不一样"的断言
    //   原本写了一条"造 8 条缓存、断言它们的 TTL 不完全相同"的用例。
    //   "摇骰子摇出不同点数"本身就带随机性，用它当断言天然不稳；
    //   而且 8 条缓存要 8 次接口调用 + 8 条 key，跑得慢、失败信息也难读。
    //   所以改成上面那条【区间断言】：它能证明"确实加了抖动、且落在预期范围内"，
    //   只是不能证明"每次都不一样" —— 这个取舍是刻意的。
    // ================================================================

    // ================================================================
    // 四、缓存版本号：写操作用它让旧缓存【同步】失效
    // ================================================================

    @Test
    @DisplayName("缓存 key 里带着当前的版本号（版本一变，旧 key 就再也拼不出来）")
    void cacheKey_shouldContainCurrentVersion() {
        String mark = uniqueMark();
        insertArticleDirectly(mark + " 版本号验证");
        articleService.pagePublished(queryByKeyword(mark));

        String version = cacheVersion.current();
        Set<String> keys = awaitCacheKeys(1, 2000);
        assertEquals(1, keys.size(), "应当只有 1 条缓存 key，实际=" + keys);

        String key = keys.iterator().next();
        // 完整 key 形如 article:page:v1:{版本号}:{页码}:{每页}:{分类}:{关键词}:{排序}:{方向}
        assertTrue(key.startsWith(KEY_PREFIX + version + ":"),
                "缓存 key 应当以「前缀 + 当前版本号」开头，实际 key=" + key);
    }

    @Test
    @DisplayName("写操作会把版本号 +1（这就是旧缓存失效的机制）")
    void write_shouldBumpCacheVersion() {
        String before = cacheVersion.current();

        articleService.create(formOf("版本号自增验证", 1), 1L);

        String after = cacheVersion.current();
        assertNotEquals(before, after,
                "写操作应当推进缓存版本号（" + before + " -> " + after + "）");
    }

    @Test
    @DisplayName("版本号推进后，旧 key 不再被使用（而不是被删除）")
    void afterBump_oldKeyShouldNoLongerBeUsed() {
        String mark = uniqueMark();
        Long id = articleService.create(formOf(mark + " 第一篇", 1), 1L);
        ArticleQuery query = queryByKeyword(mark);

        articleService.pagePublished(query);                 // 用版本 v 缓存了 1 篇
        String keyBefore = awaitCacheKeys(1, 2000).iterator().next();

        articleService.updateStatus(id, 0);                  // 下架 → 版本号 +1

        articleService.pagePublished(query);                 // 用版本 v+1 重新缓存
        Set<String> keysAfter = awaitCacheKeys(2, 2000);

        // 【这是版本号方案与"删除缓存"方案最关键的区别】
        // 旧 key 并没有被删掉（还在，等 TTL 自然过期），
        // 但因为版本号变了，新的请求再也不会去读它 —— 等于立刻失效，
        // 而且是同步生效的（INCR 与后续 GET 走同一条连接，顺序有保证），
        // 不会出现"刚写完还读到旧数据"
        assertTrue(keysAfter.contains(keyBefore),
                "旧版本的 key 应当还留在 Redis 里等 TTL 过期，实际=" + keysAfter);
        assertTrue(keysAfter.size() > 1,
                "应当同时存在新老两个版本的 key，实际=" + keysAfter);
        assertEquals(0L, articleService.pagePublished(query).getTotal(),
                "下架之后前台应当立刻看不到（读到的是新版本缓存，不是旧的那条）");
    }

    // ================================================================
    // 五、缓存 key 的归一化（纯逻辑，不需要数据库）
    // ================================================================

    @Test
    @DisplayName("语义相同的查询 -> 必须生成同一条缓存 key")
    void toCacheKey_shouldNormalizeEquivalentQueries() {
        // 不传 page/size 与显式传 1/10：查出来的结果完全一样，key 也必须一样
        ArticleQuery defaults = new ArticleQuery();
        ArticleQuery explicit = queryOf(1L, 10L, null);
        assertEquals(defaults.toCacheKey(), explicit.toCacheKey(),
                "默认参数与显式传同样参数，应当命中同一条缓存");

        // size 超过上限会被夹到 50，所以 50 / 51 / 999 实际取的都是 50 条
        // —— 这是挡住"改 size 绕过缓存"的关键
        String atLimit = queryOf(1L, 50L, null).toCacheKey();
        assertEquals(atLimit, queryOf(1L, 51L, null).toCacheKey(), "size=51 会被夹到 50，应与 50 同 key");
        assertEquals(atLimit, queryOf(1L, 999L, null).toCacheKey(), "size=999 会被夹到 50，应与 50 同 key");

        // 空/空白关键词都表示"不筛选"
        assertEquals(queryOf(1L, 10L, null).toCacheKey(), queryOf(1L, 10L, "").toCacheKey());
        assertEquals(queryOf(1L, 10L, null).toCacheKey(), queryOf(1L, 10L, "   ").toCacheKey());

        // 首尾空格要被去掉，否则 "abc" 与 "abc " 会变成两条 key
        assertEquals(queryOf(1L, 10L, "abc").toCacheKey(), queryOf(1L, 10L, "  abc  ").toCacheKey());

        // 页码非法值与默认值等价
        assertEquals(queryOf(1L, 10L, null).toCacheKey(), queryOf(0L, 10L, null).toCacheKey());
        assertEquals(queryOf(1L, 10L, null).toCacheKey(), queryOf(-3L, 10L, null).toCacheKey());
    }

    @Test
    @DisplayName("语义不同的查询 -> 必须是不同的缓存 key（否则会互相串数据）")
    void toCacheKey_shouldDifferForDifferentQueries() {
        String base = queryOf(1L, 10L, null).toCacheKey();

        assertNotEquals(base, queryOf(2L, 10L, null).toCacheKey(), "页码不同不能共用 key");
        assertNotEquals(base, queryOf(1L, 20L, null).toCacheKey(), "每页条数不同不能共用 key");
        assertNotEquals(base, queryOf(1L, 10L, "abc").toCacheKey(), "关键词不同不能共用 key");

        // 分类不同不能共用 key —— 这一条最容易写漏，
        // 漏了的后果是"按 A 分类筛出来的列表，在查 B 分类时被返回"
        ArticleQuery byCategory = queryOf(1L, 10L, null);
        byCategory.setCategoryId(7L);
        assertNotEquals(base, byCategory.toCacheKey(), "分类不同不能共用 key");

        // 排序不同不能共用 key —— 同理，漏了会出现"点排序没反应"
        ArticleQuery bySort = queryOf(1L, 10L, null);
        bySort.setSortField("viewCount");
        bySort.setSortOrder("desc");
        assertNotEquals(base, bySort.toCacheKey(), "排序不同不能共用 key");
    }

    // ================================================================
    // 私有工具方法
    // ================================================================

    /**
     * 造一个本次用例独有的标记。
     * 项目约定：分页/总数类断言必须先用唯一标记把范围圈到只剩本用例的数据，
     * 否则库里已有的真实数据会让 total 断言飘忽不定。
     */
    private String uniqueMark() {
        return "ZZC" + System.nanoTime();
    }

    /** 按关键词构造查询（关键词就是上面的唯一标记，用来圈定范围） */
    private ArticleQuery queryByKeyword(String keyword) {
        return queryOf(1L, 10L, keyword);
    }

    private ArticleQuery queryOf(Long page, Long size, String keyword) {
        ArticleQuery q = new ArticleQuery();
        q.setPage(page);
        q.setSize(size);
        q.setKeyword(keyword);
        return q;
    }

    /** 构造一篇要保存的文章 */
    private ArticleForm formOf(String title, int status) {
        ArticleForm form = new ArticleForm();
        form.setTitle(title);
        form.setContent("正文内容 —— " + title);
        form.setStatus(status);
        return form;
    }

    /**
     * 【关键工具】绕过 Service，直接用 Mapper 插一篇已发布的文章。
     *
     * 这样做【不会触发 @CacheEvict】（那个注解在 Service 上），
     * 所以缓存会保留旧结果 —— 正是靠这一点，"读缓存"和"清缓存"才能分开验证。
     */
    private void insertArticleDirectly(String title) {
        Article a = new Article();
        a.setTitle(title);
        a.setSummary("摘要 —— " + title);
        a.setContent("正文 —— " + title);
        a.setStatus(1);
        a.setViewCount(0);
        a.setIsTop(0);
        a.setAuthorId(1L);
        articleMapper.insert(a);
    }

    /** 当前 Redis 里所有文章列表缓存的 key */
    private Set<String> cacheKeys() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        return keys == null ? Set.of() : keys;
    }

    /**
     * 等缓存 key 达到期望条数（最多等 timeoutMs），返回最终数到的那批 key。
     *
     * 【为什么不能"调用完立刻数 key"—— 这是查了很久才查清的一个坑】
     *   一开始的写法是「调用完接口，紧接着 keys(...) 数一下，断言 key 已经存在」。
     *   它偶尔会红，而且每次红的用例不一样，看起来像"put 偶发不落地"。
     *   用 redis-cli MONITOR 抓命令流之后真相就清楚了：
     *
     *     [连接A] GET  "article:page:v1:1:...:ZZTXB..."   ← 缓存未命中
     *     [连接A] KEYS "article:page:v1:*"                ← 测试在这里数 key，数到 0
     *     [连接B] SET  "article:page:v1:1:...:ZZTXB..."   ← put 在 22 微秒之后才被 Redis 处理
     *
     *   也就是说：**put 确实发生了、值也完全正确，只是它是一个异步发出的写命令。**
     *   接口方法返回时，SET 可能还在路上；测试紧接着用【另一条连接】发 KEYS，
     *   两条连接上的命令到达 Redis 的先后顺序没有保证 —— KEYS 抢先被处理，就数到 0。
     *
     *   所以这里必须改成"等一小会儿再看"，而不是"立刻看"：
     *   断言的对象是【缓存最终有没有写进去】，而不是【写命令有没有在方法返回前就到 Redis】。
     *   后者本来就不是任何缓存实现会承诺的语义，拿它当断言必然会随机红。
     *
     *   ⚠️ 顺带纠正一条更早的错误结论：之前记录过"空结果（total=0）根本不会被缓存"，
     *   也是这个假象 —— MONITOR 里能清楚看到空结果被正常 SET 进去
     *   （值是 {"records":["java.util.Collections$EmptyList",[]],"total":0}）。
     *   所以防穿透（空结果也缓存一小会儿）是可以做的，现在也已经做了，见 RedisConfig#resolveTtl。
     *
     * 【为什么返回 key 集合而不是 boolean】
     *   失败时想看到"到底数到了哪几条"，返回集合能让失败信息直接带上内容，
     *   而不是只报一句 expected true but was false。
     */
    private Set<String> awaitCacheKeys(int expectedCount, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> keys = cacheKeys();
        while (keys.size() < expectedCount && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            keys = cacheKeys();
        }
        return keys;
    }

    /**
     * 取"当前唯一那条缓存"的剩余存活秒数。
     * 用例里都只写了 1 条缓存，所以取第一条即可；多于一条说明用例写错了，直接断言失败。
     */
    private long ttlOfTheOnlyCacheKey() {
        Set<String> keys = awaitCacheKeys(1, 2000);
        assertEquals(1, keys.size(), "本用例期望只有 1 条缓存 key，实际=" + keys);
        Long ttl = redis.getExpire(keys.iterator().next());
        assertNotNull(ttl, "取不到缓存 key 的 TTL");
        return ttl;
    }
}
