package com.yigalaxy.yiguixingtu.config;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * =====================================================================
 * Redis / 缓存配置
 *
 * 【这个类负责什么】
 *   把「Spring Cache 抽象」接到「Redis」上，让 Service 里一句
 *   {@code @Cacheable} 就能把方法结果缓存进 Redis，
 *   而不用在任何业务代码里手写 redisTemplate.opsForValue().get/set。
 *
 * 【用到的东西，以及它们各自是什么】
 *   · {@code @EnableCaching} —— 打开 Spring 的缓存注解开关。
 *     没有它，{@code @Cacheable} / {@code @CacheEvict} 只是普通注释：
 *     不报错、也不生效（这点很容易踩，代码看起来完全正常但缓存就是不工作）。
 *   · {@link CacheManager} —— Spring Cache 抽象里的"缓存总管"，
 *     负责按名字提供一个个 Cache 实例。业务代码只写缓存名（如 "article:page"），
 *     由它决定这个名对应哪个后端（Redis / Caffeine / 本地 Map 都行）。
 *     这里用 {@link RedisCacheManager}，即后端是 Redis。
 *   · {@link RedisCacheConfiguration} —— 某个 Cache 的具体规则：
 *     过期时间、key 前缀、序列化方式。
 *   · {@link RedisCacheWriter.TtlFunction} —— "过期时间按需决定"的钩子。
 *     它在【每次写入】时被调用，能拿到 key 和 value，
 *     因此可以对不同的值给出不同的 TTL（本项目用它区分空结果：30 秒 vs 5 分钟）。
 *   · {@link GenericJackson2JsonRedisSerializer} —— 把对象序列化成 JSON 存进 Redis。
 *     之所以不用默认的 JDK 序列化（{@code JdkSerializationRedisSerializer}）：
 *     JDK 序列化出来的是一堆二进制，用 redis-cli 看是一串乱码、没法排查；
 *     而且它要求被序列化的类都实现 Serializable，且类结构一变旧数据就读不出来。
 *     JSON 存进去人眼可读，跨语言、跨版本都更宽容。
 *
 * 【代码构造为什么是这样】
 *   1. 先造一个"公共规则"（baseConfig）：所有缓存默认都用它
 *   2. 再针对个别缓存名覆盖差异（目前只有 article:page 需要单独说明）
 *   3. 最后交给 RedisCacheManager.builder() 组装
 *   这种"默认 + 局部覆盖"是 Spring 配置里很常见的写法，
 *   好处是新增缓存时不用重复写一遍序列化和前缀。
 *
 * 【注意：这里没有自定义 RedisTemplate】
 *   项目里已经有 {@code StringRedisTemplate}（Spring Boot 自动配好的），
 *   UserAuthCache 用的就是它 —— 存取用户的认证字段用 Hash 结构、字符串就够。
 *   缓存这边则由 RedisCacheManager 自己管序列化。
 *   没有实际使用者的 Bean 是死代码，所以不提前加。
 * =====================================================================
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /** 文章前台已发布列表的缓存名 */
    public static final String CACHE_ARTICLE_PAGE = "article:page";

    /**
     * 正常内容的缓存时长：5 分钟。
     * 选这个值是在"缓存命中率"和"内容新鲜度"之间取平衡：
     * 太短（几秒）等于没缓存；太长（几小时）会让"改了文章要等很久才生效"变得明显
     * ——虽然写操作会主动让缓存失效（见 ArticleCacheVersion），
     * TTL 只是最后的保险，但保险太长也会让人怀疑系统没更新。
     */
    private static final Duration TTL_NORMAL = Duration.ofMinutes(5);

    /**
     * 空结果的缓存时长：30 秒。
     *
     * 【这就是"防缓存穿透"】
     *   穿透指的是：有人用一堆【必然查不到结果】的条件反复刷接口
     *   （比如把 keyword 拼成随机串），每次缓存都不命中，请求全部落到数据库。
     *   缓存通常只存"有结果"的查询，所以这类请求等于绕过了缓存 ——
     *   恶意刷的话足以把库拖垮。
     *
     *   防法很直接：**把"没有结果"这件事本身也缓存起来**。
     *   既然查过了确实没有，30 秒内再问就直接告诉它"没有"，不用再打库。
     *
     * 【为什么是 30 秒这么短】
     *   因为它缓存的是一份"空"，而空是会变的 ——
     *   用户可能刚好在这期间发布了符合条件的新文章。
     *   TTL 短一点，最坏情况的"看不到新文章"就只有 30 秒；
     *   而对攻击流量来说，30 秒已经足够把重复查询挡掉了。
     *   （正常内容的 5 分钟 TTL 反而不能太短，因为那是用户真正想看的数据。
     *     两类内容诉求不同，所以用 TtlFunction 按内容分别给时长。）
     */
    private static final Duration TTL_EMPTY = Duration.ofSeconds(30);

    /**
     * TTL 随机抖动的上限（秒）。
     *
     * 【为什么过期时间要加随机值】—— 这是防"缓存雪崩"：
     *   如果一批缓存在【同一时刻】被写入（比如服务重启后第一批请求、
     *   或者定时任务批量预热），它们的过期时间就会完全一致，
     *   于是会在同一秒集体失效，请求瞬间全部压到数据库上。
     *   给每条缓存的 TTL 加一个 0~60 秒的随机量，过期时间就被摊开了。
     */
    private static final int TTL_JITTER_MAX_SECONDS = 60;

    /**
     * 组装缓存管理器。
     *
     * @param connectionFactory Spring Boot 根据 application.properties 里的
     *                          spring.data.redis.* 自动配好的连接工厂
     *                          （测试环境里它指向 Testcontainers 起的 Redis 容器）
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        // ---------------- 序列化器：值用 JSON，key 用纯字符串 ----------------
        //
        // 值：GenericJackson2JsonRedisSerializer
        //   它会在 JSON 里额外写一个类型标记字段，反序列化时才知道该还原成哪个类
        //   （缓存里存的是 IPage<ArticleVO>，读出来必须还是这个类型，而不是
        //    一个 LinkedHashMap —— 后者会让调用方在使用时报 ClassCastException）。
        //
        // .configure(...) 是往它内部的 ObjectMapper 上补配置：
        //   JavaTimeModule 是【关键的一步】。文章里有 createTime/updateTime 这类
        //   java.time.LocalDateTime 字段，Jackson 默认【不知道怎么序列化它们】，
        //   不加这个模块会在写缓存时直接抛 InvalidDefinitionException。
        //   disable(WRITE_DATES_AS_TIMESTAMPS) 则是让它写成
        //   "2026-09-10T10:00:00" 这种可读形式，而不是一串时间戳数字
        //   —— 排查问题时用 redis-cli 看一眼就知道是什么时候，方便很多。
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer()
                        .configure(mapper -> {
                            mapper.registerModule(new JavaTimeModule());
                            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                        });

        // key：StringRedisSerializer
        //   缓存 key 拼出来本来就是字符串。若用 JSON 序列化 key，
        //   Redis 里会看到带引号的 "\"article:page::1:10\""，既难看也容易配错前缀。
        StringRedisSerializer keySerializer = new StringRedisSerializer();

        // ---------------- 公共规则 ----------------
        RedisCacheConfiguration baseConfig = RedisCacheConfiguration.defaultCacheConfig()
                // computePrefixWith：决定 key 在 Redis 里的最终前缀。
                // Spring 默认是 `缓存名::`；这里改成 `缓存名:v1:`，
                // 好处是【版本化】：哪天缓存里存的数据结构变了
                // （比如 ArticleVO 加字段、字段改名），
                // 只要把 v1 改成 v2，老缓存立刻全部失效、不会被当成新结构读出来。
                // 这比手动上 Redis 敲 FLUSHDB 安全得多，也说得清为什么。
                .computePrefixWith(cacheName -> cacheName + ":v1:")
                // 过期时间交给下面的方法按内容决定（正常 5 分钟 + 随机抖动；空结果 30 秒）
                .entryTtl(this::resolveTtl)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(keySerializer))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        // ---------------- 缓存写入器：用默认策略就行 ----------------
        //
        // 【这里原来写的是 BatchStrategies.keys()，现在改回默认了 —— 说明一下为什么】
        //   最初的方案是"写操作时用 @CacheEvict(allEntries = true) 清空整个缓存"。
        //   那个方案踩了一个很隐蔽的坑：Spring Data Redis 的 RedisCache.clear()
        //   （allEntries 背后调用的东西）【不是同步完成的】—— 方法返回时还没删完，
        //   删除在后台继续跑。表现出来就是"发布文章后刷新前台，有时看得到有时看不到"，
        //   测试也会随机红。
        //   当时为了让它"至少在语义上同步"，显式指定了 BatchStrategies.keys()
        //   （走 Lua 脚本，KEYS + DEL 一起原子执行）。但 KEYS 会阻塞 Redis 的单线程，
        //   是个拿生产安全换局部语义的交换。
        //
        //   后来换了方案：不再删缓存，改成【版本号】（见 ArticleCacheVersion）。
        //   版本号一变，旧 key 再也拼不出来，等于瞬间全部失效 —— 一条数据都不用删。
        //   顺带的好处是：
        //     · 完全不需要 clear()，也就不用跟它的异步语义较劲了
        //     · 不需要 KEYS / SCAN，生产上更安全
        //     · 失效代价从 O(缓存条数) 降到 O(1)（一次 INCR）
        //   所以这里改回默认的写入器：没有 clear 调用，批量策略就用不上了。
        RedisCacheWriter cacheWriter = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory);

        return RedisCacheManager.builder(cacheWriter)
                .cacheDefaults(baseConfig)
                // 针对具体缓存名做覆盖。目前 article:page 用的就是公共规则，
                // 之所以显式列出来，是为了以后它需要单独的 TTL 或前缀时，
                // 改动点就在这一行，不用去猜它到底走的哪套配置。
                .withCacheConfiguration(CACHE_ARTICLE_PAGE, baseConfig)
                .build();
    }

    /**
     * 决定某一条缓存能活多久。每次写入缓存时由 {@link RedisCacheWriter.TtlFunction} 调用。
     *
     * 【为什么用 TtlFunction 而不是 entryTtl(Duration)】
     *   entryTtl(Duration) 是"整个缓存一个固定时长"，写进去的每一条都一样；
     *   TtlFunction 是"每次写入时现算一次"，才能做到按内容给不同时长。
     *   本项目用它做两件事：
     *     · 空结果给 30 秒（防穿透）
     *     · 每条记录的 TTL 再加一点随机抖动（防雪崩）
     *
     * 【怎么判断"这条结果是空的"】
     *   缓存里存的是 {@code IPage<ArticleVO>}，所以判断它的 records 是不是空即可。
     *   注意 value 也有可能是 Spring 用来表示 null 的 {@code NullValue} 包装
     *   （见 AbstractValueAdaptingCache.toStoreValue）——那种情况走 else 分支，
     *   照样有 TTL，不会变成永不过期的缓存。
     *
     * @param key   缓存的 key（本版本用不到，但接口给了就必须接住）
     * @param value 要写入的值，用来区分"空结果"与"有内容"
     * @return 该条缓存的存活时长
     */
    private Duration resolveTtl(Object key, Object value) {
        int jitter = ThreadLocalRandom.current().nextInt(TTL_JITTER_MAX_SECONDS);

        if (value instanceof IPage<?> page) {
            boolean empty = page.getRecords() == null || page.getRecords().isEmpty();
            if (empty) {
                return TTL_EMPTY.plusSeconds(jitter % 10);
            }
        }
        return TTL_NORMAL.plusSeconds(jitter);
    }
}
