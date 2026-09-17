package com.yigalaxy.yiguixingtu.article.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * =====================================================================
 * 文章列表缓存的「版本号」—— 用来一键作废所有已缓存的列表
 *
 * ============ 它解决什么问题 ============
 *
 * 【常规做法，以及它在这里为什么不成立】
 *   缓存了列表之后，写操作（发文/改文/删文）要让缓存失效，最直观的写法是
 *   {@code @CacheEvict(allEntries = true)} —— "把这个缓存名下的所有 key 都删掉"。
 *
 *   但实测发现：Spring Data Redis 的 {@code RedisCache.clear()}
 *   （也就是 allEntries 背后调用的东西）【是异步的】——
 *   方法返回时还没删完，删除在后台继续跑。
 *   实测数据（只碰缓存、不碰数据库的探针）：
 *     · put 之后立刻查 Redis            → 已写入        （同步）
 *     · clear() 之后立刻查 Redis        → 还在！        （没删完）
 *     · clear() 之后等 500ms 再查        → 已删除        （后台删掉了）
 *     · evict(单个 key) 之后立刻查       → 已删除        （同步）
 *   注意：显式指定 {@code BatchStrategies.keys()} 也【不能】把它变同步，
 *   所以这不是"配置没调对"，而是这个 API 本身的语义。
 *
 *   后果是业务上会看到"发布文章之后刷新前台，有时看得到、有时看不到" ——
 *   取决于那次刷新有没有赶在后台删除完成之前。这种"偶发不一致"最难排查，
 *   写测试也会变成随机红。
 *
 * 【换成版本号之后为什么就好了】
 *   不再去"删掉旧缓存"，而是给缓存 key 加一层版本：
 *     · Redis 里存一个计数器 article:page:version
 *     · 缓存的 key 形如  article:page:v1:{版本号}:{分页条件}
 *     · 写操作只做一件事：把版本号 INCR 一下
 *   版本号一变，之前所有的缓存 key 就【再也拼不出来】了 ——
 *   等于一瞬间全部作废，但一条数据都不用删。旧数据留着等 TTL 自然过期即可。
 *
 *   INCR 是 Redis 的单条原子命令、【同步返回】，所以
 *   "写完立刻读一定读到新数据"这个语义是有保证的。
 *
 * 【顺带拿到的三个好处】
 *   ① 写入侧的代价从 O(缓存条数) 变成 O(1)：不管缓存里有 10 条还是 10 万条，
 *      失效都只是一次 INCR
 *   ② 完全避开了 KEYS / SCAN —— 那两个命令在大 keyspace 上是有风险的
 *      （KEYS 会阻塞 Redis 单线程），现在压根不需要批量匹配 key 了
 *   ③ 缓存失效这件事不再依赖"注解生效的时机"，而是一次显式的自增调用，
 *      日志、断点都能看见
 *
 * 【代价 / 需要注意的地方】
 *   · 版本号只增不减，理论上会一直变大。long 的范围用不完，Redis 的计数器
 *     也不会溢出（到 2^63 才回绕，现实中不可能）
 *   · 旧版本的缓存 key 不会被主动清理，靠 TTL 过期。
 *     这意味着 Redis 里短时间会同时存在"新老两个版本"的少量 key，
 *     但只要 TTL 正常（5 分钟 + 抖动），它们会自己消失，不会无限增长
 *
 * =====================================================================
 * 【用到的东西】
 *   · {@code @Component} —— 交给 Spring 容器管理，
 *     这样它既能被 Service 注入调用，也能在 SpEL 里被按 bean 名引用
 *   · {@link StringRedisTemplate} —— Spring Boot 自动配好的 Redis 客户端。
 *     版本号就是一个普通字符串计数器，用字符串模板最直接
 *     （项目里 {@code auth/cache/UserAuthCache} 也是同样的用法）
 *   · {@code opsForValue().increment(key)} —— 对应 Redis 的 INCR：
 *       · 原子性：多个请求同时发文，计数也只会各加一次，不会丢
 *       · key 不存在时自动从 0 开始加，所以不需要初始化
 *
 * =====================================================================
 * 【它在缓存链路里的位置】
 *   读：ArticleServiceImpl.pagePublished 上的
 *       {@code @Cacheable(key = "@articleCacheVersion.current() + ':' + #query.toCacheKey()")}
 *       —— SpEL 里 {@code @beanName} 就是"取容器里这个 bean 并调用它的方法"，
 *       所以每次算缓存 key 时都会现取一次版本号
 *   写：create / update / updateStatus / remove 四个方法末尾调用 {@link #bump()}
 *       —— 这四个调用点各自都有对应的测试盯着（ArticleCacheTest），
 *       将来新增写接口时，照着写并在测试里补一条即可
 * =====================================================================
 */
@Slf4j
@Component
public class ArticleCacheVersion {

    /**
     * 列表缓存版本号在 Redis 里的 key。
     *
     * 【为什么是 Redis 而不是一个 JVM 内存里的 AtomicLong】
     *   内存版只在【单实例】下正确：一旦部署两个后端实例，
     *   A 实例发文只让自己内存里的版本号 +1，B 实例的缓存 key 还是老版本、
     *   依然会返回旧数据。放 Redis 才能让所有实例看到同一个版本号。
     *   （这也是"缓存失效"这类状态天然属于外部存储的原因）
     */
    static final String VERSION_KEY = "article:page:version";

    /**
     * 详情缓存版本号在 Redis 里的 key。
     *
     * 【为什么详情要单独一个计数器，而不是和列表共用】
     *   因为两者"需要失效的时机"不同 —— 多出来的那一类来自【浏览量的定时落库】：
     *     · 列表缓存里也有一份 view_count，但它【不做合并】，只是原样显示库里的值。
     *       落库之后它还是旧的，等 TTL 到了自然刷新。数字只会滞后，不会往回跳。
     *     · 详情缓存不一样：它返回的是「缓存里的快照 + Redis 里的增量」。
     *       落库会同时做两件事 —— 库里的快照变大、Redis 里的增量清零。
     *       如果详情缓存里那份快照还是旧的，就会出现
     *         「旧快照 0 + 新增量 1 = 1」
     *       而落库前明明是 3 —— **数字当着用户的面往回跳**。
     *   （这个 bug 是全量测试抓出来的：ArticleViewCountTest 的
     *     "落库之后继续访问 -> 从新的基线往上加" 那条用例变红。）
     *   所以：文章被写 → 两个版本号一起 +1；浏览量落库 → 只推动详情那个。
     */
    static final String DETAIL_VERSION_KEY = "article:detail:version";

    /** Redis 里还没这个 key 时的版本号 */
    private static final String INITIAL_VERSION = "0";

    private final StringRedisTemplate redis;

    public ArticleCacheVersion(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 取当前版本号，供拼装【列表】缓存 key 使用。
     *
     * @return 当前版本号字符串；Redis 里还没有时返回 "0"
     */
    public String current() {
        return read(VERSION_KEY);
    }

    /**
     * 取当前版本号，供拼装【详情】缓存 key 使用。
     *
     * 【为什么详情要读另一个计数器】见上面 DETAIL_VERSION_KEY 的注释 ——
     * 一句话：浏览量定时落库会改变详情里的快照，但不会改变列表的内容。
     */
    public String currentDetail() {
        return read(DETAIL_VERSION_KEY);
    }

    /**
     * 读一个版本号计数器的当前值。
     *
     * ============================================================
     * ⚠️【全项目唯一一处"必须自己兜住异常"的缓存读】
     * ============================================================
     * 这个方法会被 {@code @Cacheable} 的 SpEL key 表达式引用，例如
     *     {@code key = "@articleCacheVersion.current() + ':' + #query.toCacheKey()"}
     * 而 SpEL 的求值发生在 Spring 的 {@code CacheAspectSupport.generateKey()} 里，
     * 那一步【在 try/catch 之外】：
     *     CacheAspectSupport.java:451   Object key = generateKey(context, NO_RESULT);
     *     CacheAspectSupport.java:471   getErrorHandler().handleCacheGetError(...);  ← 兜底在这里
     * （依据：解压 spring-context 7.0.9 的 sources jar 逐行核对，不是照文档猜的。）
     *
     * ⇒ 结论有两层，第二层最容易漏：
     *   ① Redis 读失败时异常会一路冒到接口层 —— 列表/详情/统计/分类/标签/归档/RSS
     *      全部变成 500，而不是"缓存失效、回落查库"。
     *   ② 【哪怕将来补上 CacheErrorHandler 也管不到这里】—— 它只包住
     *      Cache.get/put 那一段，包不住 key 的生成。所以这一层兜底不能省。
     *
     * 项目在别处的口径是"缓存出问题绝不能影响主流程"
     * （见 TokenBlacklist / UserAuthCache / ArticleViewCounter / IdempotencyService），
     * 这里必须保持一致 —— 缓存是加速手段，不是依赖。
     *
     * ============================================================
     * 【为什么返回一个每次都不一样的值，而不是返回 "0" / INITIAL_VERSION】
     * ============================================================
     * 返回值会被拼进缓存 key。返回固定值意味着"Redis 坏掉期间的所有请求
     * 都去命中【同一个 key】"—— 而那个 key 里可能存着更早的数据，
     * 于是故障期间所有人读到的是过期内容，且完全看不出是缓存出了问题。
     * 返回每次都不同的值 ⇒ 拼出来的 key 永远不命中 ⇒ 相当于自动降级成
     * "绕过缓存直接查库"：功能正确，代价只是这段时间缓存暂时不生效。
     * 这是这个故障场景下唯一正确的行为。
     */
    private String read(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? INITIAL_VERSION : v;
        } catch (Exception e) {
            log.warn("读取缓存版本号失败, key={} —— 本次返回一个不重复的值，缓存自动降级为查库: {}",
                    key, e.getMessage());
            return "unavailable-" + UUID.randomUUID();
        }
    }

    /**
     * 文章被写（新建 / 编辑 / 发布下架 / 删除）之后调用。
     *
     * 【两个版本号都要推进】
     *   因为一次文章的写操作既改变了列表的内容（标题、摘要、排序位置），
     *   也改变了详情的内容 —— 两个缓存都不能再用了。
     *
     * 【调用时机】必须在业务写操作【成功之后】调用。
     *   如果写操作最终抛异常回滚了，却先把版本号推进了，
     *   后果只是"缓存被白白作废一次、下次重新查库"——
     *   多一次数据库查询，不会产生错误数据。
     *   反过来（先写库成功、后忘记推版本号）才会留下脏缓存，
     *   所以这里的取向是"宁可多作废一次，也不能漏"。
     *
     * @return 自增之后的列表版本号（便于日志/排查，测试也用它断言）
     */
    public long bump() {
        long next = incr(VERSION_KEY);
        incr(DETAIL_VERSION_KEY);
        return next;
    }

    /**
     * 浏览量定时落库【成功之后】调用，只让详情缓存失效。
     *
     * 【为什么列表缓存不需要跟着失效】
     *   列表里的 view_count 是"原样展示库里的值"，落库后它只是暂时偏小、
     *   TTL 到了自然刷新，不会出错；而详情要做"快照 + 增量"的合并，
     *   快照过期就会让数字往回跳（详见 DETAIL_VERSION_KEY 的注释）。
     *   只推动必要的那个，能让列表缓存不被每 5 分钟一次的无谓失效拖累。
     *
     * @return 自增之后的详情版本号
     */
    public long bumpAfterViewSync() {
        return incr(DETAIL_VERSION_KEY);
    }

    /**
     * 把某个版本号 +1。
     *
     * @return 自增之后的值
     */
    private long incr(String key) {
        Long next = redis.opsForValue().increment(key);
        // increment 正常不会返回 null（key 不存在时会从 0 开始），
        // 这里兜一下是为了避免拆箱 NPE —— 宁可抛一个清楚的异常，
        // 也不要让一个莫名其妙的 NPE 出现在业务代码的堆栈里
        if (next == null) {
            throw new IllegalStateException("推进文章缓存版本号失败，Redis 未返回结果: " + key);
        }
        return next;
    }
}
