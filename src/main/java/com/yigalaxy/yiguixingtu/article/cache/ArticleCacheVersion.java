package com.yigalaxy.yiguixingtu.article.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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
@Component
public class ArticleCacheVersion {

    /**
     * 版本号在 Redis 里的 key。
     *
     * 【为什么是 Redis 而不是一个 JVM 内存里的 AtomicLong】
     *   内存版只在【单实例】下正确：一旦部署两个后端实例，
     *   A 实例发文只让自己内存里的版本号 +1，B 实例的缓存 key 还是老版本、
     *   依然会返回旧数据。放 Redis 才能让所有实例看到同一个版本号。
     *   （这也是"缓存失效"这类状态天然属于外部存储的原因）
     */
    static final String VERSION_KEY = "article:page:version";

    /** Redis 里还没这个 key 时的版本号 */
    private static final String INITIAL_VERSION = "0";

    private final StringRedisTemplate redis;

    public ArticleCacheVersion(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 取当前版本号，供拼装缓存 key 使用。
     *
     * @return 当前版本号字符串；Redis 里还没有时返回 "0"
     */
    public String current() {
        String v = redis.opsForValue().get(VERSION_KEY);
        return v == null ? INITIAL_VERSION : v;
    }

    /**
     * 把版本号 +1，让之前所有已缓存的列表【立刻失效】。
     *
     * 【调用时机】必须在业务写操作【成功之后】调用。
     *   如果写操作最终抛异常回滚了，却先把版本号推进了，
     *   后果只是"缓存被白白作废一次、下次重新查库"——
     *   多一次数据库查询，不会产生错误数据。
     *   反过来（先写库成功、后忘记推版本号）才会留下脏缓存，
     *   所以这里的取向是"宁可多作废一次，也不能漏"。
     *
     * @return 自增之后的版本号（便于日志/排查，测试也用它断言）
     */
    public long bump() {
        Long next = redis.opsForValue().increment(VERSION_KEY);
        // increment 正常不会返回 null（key 不存在时会从 0 开始），
        // 这里兜一下是为了避免拆箱 NPE —— 宁可抛一个清楚的异常，
        // 也不要让一个莫名其妙的 NPE 出现在业务代码的堆栈里
        if (next == null) {
            throw new IllegalStateException("推进文章列表缓存版本号失败，Redis 未返回结果");
        }
        return next;
    }
}
