package com.yigalaxy.yiguixingtu.config;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
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

    /** 文章详情的缓存名（前台只缓存已发布的） */
    public static final String CACHE_ARTICLE_DETAIL = "article:detail";

    /** 站点统计的缓存名（首页那三个数字） */
    public static final String CACHE_ARTICLE_STATS = "article:stats";

    /**
     * 标签列表的缓存名（前台标签云：每个标签 + 它下面【已发布】的文章数）。
     *
     * 【为什么它值得缓存】
     *   这个列表要用一条 JOIN + GROUP BY 算"每个标签有几篇已发布文章"，
     *   而它是首页/侧边栏那种"每次打开都请求"的接口 —— 典型的"计算不贵但频率高"。
     *
     * 【⚠️ 它为什么和文章缓存共用同一个版本号】
     *   标签列表里的文章数会随"文章发布/下架/删除/改标签"而变，
     *   而那些操作本来就会推进 {@code article:page:version}（见 ArticleCacheVersion）。
     *   共用同一个版本号，等于"文章一变，标签列表也一起失效"，
     *   不需要再维护第二套失效逻辑 —— 少一套需要同步的机制，
     *   就少一处"忘了 bump"的可能。
     *   （代价：改一篇文章会让标签列表也重新查一次库。标签列表本身就轻，可以接受。）
     */
    public static final String CACHE_TAG_LIST = "tag:list";

    /**
     * 分类列表的缓存名。
     *
     * 【为什么它值得缓存】前端首页改成 SSR 之后，每次服务端渲染都会请求一次
     * /category/list（首页的筛选条要用它），而它【只在分类被增删改时才变】——
     * 典型的"读多写极少"。
     * 和标签列表一样，key 里带的是文章缓存版本号（分类写操作会推进它），
     * 所以不需要单独维护第二套失效逻辑。
     */
    public static final String CACHE_CATEGORY_LIST = "category:list";

    /**
     * 归档的缓存名（按年月分组的文章列表）。
     *
     * 【为什么它也能共用文章版本号】归档内容 = "已发布文章按时间分组"，
     * 它只在文章被增删改（或发布/下架）时变 —— 而那几个操作本来就会推进版本号。
     * 所以和列表、统计一样，key 只用版本号即可，没有第二套失效逻辑。
     */
    public static final String CACHE_ARTICLE_ARCHIVE = "article:archive";

    /**
     * RSS 订阅源数据的缓存名（最近 20 篇的正文）。
     *
     * 【为什么也要缓存】RSS 阅读器按固定间隔来拉，而它一次要 20 篇 longtext 正文；
     * 不缓存就是每半小时把 20 个大字段从库里读一遍。同样共用文章版本号 ——
     * 发文/改文/下架都会推进它，所以缓存不会给出旧内容。
     */
    public static final String CACHE_ARTICLE_RSS = "article:rss";

    /**
     * 友链列表的缓存名（前台友情链接页）。
     *
     * 【为什么它值得缓存】友链页是"每次打开都要拉一次"的接口，
     * 而它的内容【只有管理员手工改的时候才变】—— 典型的"读多写极少"。
     *
     * 【⚠️ 它为什么不用文章那个版本号】
     *   友链和文章毫无关系（没有任何表引用它）。如果共用
     *   {@code article:page:version}，"加一条友链"会把文章列表 / 详情 / 归档 / RSS /
     *   分类 / 标签六份缓存一起作废 —— 功能不错，但纯属无谓的重新查库，
     *   还会让人误以为"文章那边的缓存为什么老在重建"。
     *   所以 F5 的四个内容模块（友链 / 项目 / 收藏 / 关于）共用
     *   {@code ContentCacheVersion} 那一个计数器：谁的内容变，就推进谁的版本号。
     */
    public static final String CACHE_FRIEND_LINK_LIST = "link:list";

    /**
     * 项目列表的缓存名（前台"我的项目"页）。
     *
     * 【为什么它值得缓存，且为什么和友链用同一个版本号】
     *   理由与友链完全一样（读多写极少、和文章无关）。
     *   两者共用 {@code ContentCacheVersion} 那一个计数器：
     *   代价只是"改一条友链会让项目列表也重建一次"，而这类内容一个月都未必改一次；
     *   换成两个计数器则是两份要维护、要清理、要写测试的状态。
     *   （和"标签与分类共用文章版本号"是同一个取舍：宁可多作废一次，也不多养一套状态。）
     */
    public static final String CACHE_PROJECT_LIST = "project:list";

    /**
     * 收藏列表的缓存名（前台"收藏"页）。
     * 与前三个内容模块同理：读多写极少、与文章无关，共用内容缓存版本号。
     */
    public static final String CACHE_FAVORITE_LIST = "favorite:list";

    /**
     * 关于页的缓存名。
     *
     * 【为什么单条数据也要缓存】导航栏 / 页脚 / 关于页都可能读它，
     * 而它【几个月才改一次】—— 典型的"读多写极少"。缓存它几乎不会有失效压力，
     * 却省掉每次打开页面都查一次库。
     *
     * 【key 仍然只用版本号】这份数据只有一份、没有参数，所以 key = 版本号即可；
     * 后台保存会推进那个版本号，于是"改完立刻生效"同样成立。
     * 它和另外三个内容模块共用 ContentCacheVersion 那一个计数器
     * （共用的取舍见 ContentCacheVersion 的类注释）。
     */
    public static final String CACHE_ABOUT = "about:detail";

    /**
     * 音乐列表的缓存名（前台「音乐」页）。
     *
     * 【为什么它值得缓存】音乐页是"每次打开都要拉一次"的接口，而曲目表
     * 【只有管理员在后台上传曲目时才变】—— 典型的"读多写极少"。
     * 而且列表里带的是歌词（TEXT，一条几百字到几 KB），一次返回全部曲目时
     * 这些长文本都要从库里读出来，缓存掉的收益比友链那种"只有一行文字"的模块更明显。
     *
     * 【⚠️ 它为什么不用文章那个版本号】
     *   与友链 / 项目 / 收藏 / 关于同一条理由：音乐和文章毫无关系（没有任何表引用它）。
     *   如果共用 {@code article:page:version}，"上传一首歌 / 改一句歌词"会把
     *   文章列表 / 详情 / 归档 / RSS / 分类 / 标签六份缓存一起作废 ——
     *   功能不错，但纯属无谓的重新查库，还会让人以为"文章那边的缓存为什么老在重建"。
     *   所以它和 F5 那四个内容模块共用 {@code ContentCacheVersion} 那一个计数器。
     */
    public static final String CACHE_MUSIC_LIST = "music:list";

    /**
     * 站点设置的缓存名。
     *
     * 【为什么它值得缓存 —— 它的读法比前面任何一个模块都频繁】
     *   其余模块的缓存是"某个页面要用"（音乐页、友链页各读各的），
     *   而这份数据驱动的是【整站的外壳】：页眉的站点名、页脚的版权与备案号、
     *   首页的公告与每页条数、文章页的评论开关 ——
     *   也就是【每一次页面渲染都要读它】，包括错误页和 404 页。
     *   而它只在管理员手工改的时候才变，典型的"读多写极少"。
     *
     * 【key 仍然只用版本号】这份数据只有一份、没有参数（和 about:detail 一样）；
     *   保存会推进版本号，所以"改完立刻生效"同样成立。
     *   它与友链 / 项目 / 收藏 / 关于 / 音乐共用 ContentCacheVersion 那一个计数器
     *   （共用的取舍见 ContentCacheVersion 的类注释：宁可多作废一次，
     *    也不多养一套要维护、要清理、要写测试的状态）。
     */
    public static final String CACHE_SITE_SETTING = "setting:detail";

    /**
     * 统计结果的缓存时长：60 秒。
     * 为什么比列表缓存的 5 分钟短得多，见下面 resolveStatsTtl 的注释
     * （一句话：里面那个"总浏览量"是异步落库的，天生会滞后）。
     */
    private static final Duration TTL_STATS = Duration.ofSeconds(60);

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

        // ---------------- 缓存写入器：用 locking 版本，为了防击穿 ----------------
        //
        // 【这里原来写的是 BatchStrategies.keys()，后来改成默认，现在改成 locking】
        //   三次变化对应三次认识，按顺序说清楚：
        //
        //   ① 最初用 @CacheEvict(allEntries = true) 清缓存 ——
        //      踩到一个很隐蔽的坑：RedisCache.clear() 【不是同步完成的】，
        //      方法返回时还没删完，删除在后台继续跑。业务表现是
        //      "发布文章后刷新前台，有时看得到有时看不到"。当时为了让语义至少同步，
        //      显式指定了 BatchStrategies.keys()（Lua 脚本原子执行），
        //      但 KEYS 会阻塞 Redis 单线程，是拿生产安全换局部语义。
        //
        //   ② 改用【版本号】方案后（见 ArticleCacheVersion）根本不需要 clear() 了，
        //      于是去掉批量策略、改回默认写入器。
        //
        //   ③ 现在做详情缓存要【防击穿】，需要"未命中时只有一个人去加载"这个能力。
        //      它由 @Cacheable(sync = true) + lockingRedisCacheWriter 一起提供：
        //      sync = true 会让 Spring 走 Cache.get(key, Callable) 那条路，
        //      而 locking 写入器在加载前会先抢一把 Redis 里的锁
        //      （内部就是 SETNX），抢到的去查库、其余等锁后读缓存。
        //
        //      【为什么用框架的，而不是自己写 SETNX + 双重检查】
        //        那正是 LockingRedisCacheWriter 内部在做的事，只是它把
        //        锁超时、异常时释放、抢不到时重试这些细节都处理好了。
        //        自己写一遍只会多出几个能写错的地方，而且错了的表现是
        //        "偶发多查一次库"，几乎不可能被发现。
        //
        //      【代价】每次缓存未命中都会多一次 SETNX/DEL 的往返。
        //      命中时不受影响（正常路径还是直接 GET）。
        //      对本项目"读多写少、缓存命中率极高"的形状来说，这个代价可以忽略；
        //      如果哪天缓存命中率很低，锁的开销就会变成负担，那时该重新评估。
        //
        //      ============================================================
        //      ⚠️【更正 + 修复】上面"它把锁超时处理好了"这句是错的，实测踩到
        //      ============================================================
        //      单参的 lockingRedisCacheWriter(connectionFactory) 用的是
        //      DefaultRedisCacheWriterConfigurer 的默认值，其中
        //      lockTtlFunction = TtlFunction.persistent() —— 【锁 key 永不过期】。
        //      发出的命令是不带 EX/PX 的 SETNX；而拿不到锁时的等待
        //      （checkAndPotentiallyWaitUntilUnlocked）是
        //      while (锁还在) { Thread.sleep(间隔) } 的【无界循环】，没有重试上限。
        //
        //      【依据】反编译 spring-data-redis 4.1.1 的字节码逐条核对（defaultRedisCacheWriter
        //      Configurer 的构造函数、doLock、checkAndPotentiallyWaitUntilUnlocked、
        //      createCacheLockKey 四个方法），不是照文档猜的 —— 官方文档只写了
        //      "支持防击穿"，没有任何一处说明锁默认不过期。
        //
        //      【更麻烦的是锁的粒度】createCacheLockKey(String) 只接收缓存名一个参数，
        //      产出的 key 形如 "article:detail~lock"。也就是说这是 article:detail
        //      这个名字下【所有文章共用】的一把锁，不是一篇文章一把。
        //
        //      ⇒ 后果：容器被 OOM-kill（compose 里 backend 的 mem_limit 是 640m）
        //        或者重启，恰好落在"抢到锁 → 查库 → 写缓存"这三步中间，
        //        那个 ~lock key 就永久留在 Redis 里。此后【每一篇文章】的详情请求
        //        都在这里自旋，全站详情页一起打不开，而且不会自愈 ——
        //        只能人工上 Redis 把那个 key 删掉。这是本项目唯一一个
        //        "不依赖任何业务数据出错、只靠一次进程被杀就能导致整站读接口持续不可用"
        //        的配置，所以下面的三项全部显式传，不再吃默认值。
        //
        //      【三个参数各自为什么是这个数】
        //        · lockTtl = 10 秒 —— 锁只保护"查一次库 + 写一次缓存"。
        //          详情查询实测是 0.05ms 量级；就算数据库慢到 Hikari 的
        //          connection-timeout（3 秒，见 application.properties）上限，也远不到 10 秒。
        //          反过来，真出事时最多等 10 秒就自愈，不需要人工介入。
        //          这是"正常路径绝对不会误释放"和"故障恢复足够快"之间的取值。
        //        · sleepTime = 50ms —— 默认值是 Duration.ZERO，即 Thread.sleep(0) 的
        //          忙等：拿不到锁的线程会以 CPU 全速反复问 Redis"锁还在吗"，
        //          既烧 CPU 又白打 Redis。50ms 是 Spring Data Redis 3.x 时期的默认值，
        //          拿它当轮询间隔既有依据，也不会拖慢正常路径（锁的持有时间远小于它）。
        //        · batchStrategy = SCAN(1000) —— 只在 clear() / clean() 时才被用到。
        //          项目现在靠版本号失效、根本不调用 clear()（见上面 ②），所以传什么
        //          当前都没有实际影响；不沿用默认的 BatchStrategies.keys() 是因为
        //          KEYS 会阻塞 Redis 单线程（见上面 ① 里的教训），SCAN 分批更安全。
        RedisCacheWriter cacheWriter = RedisCacheWriter.lockingRedisCacheWriter(
                connectionFactory,
                Duration.ofMillis(50),
                RedisCacheWriter.TtlFunction.just(Duration.ofSeconds(10)),
                BatchStrategies.scan(1000));

        // ---------------- 统计缓存的规则（只是 TTL 不同） ----------------
        // 列表缓存和统计缓存的序列化、前缀规则完全一样，只有过期时间不同，
        // 所以这里从 baseConfig 派生一份、只覆盖 TTL ——
        // 而不是把上面那一大段序列化配置再抄一遍。
        RedisCacheConfiguration statsConfig = baseConfig
                .entryTtl(this::resolveStatsTtl);

        return RedisCacheManager.builder(cacheWriter)
                .cacheDefaults(baseConfig)
                // 针对具体缓存名做覆盖。三个都显式列出来，
                // 是为了让"哪个缓存走哪套 TTL"在这几行里一眼可见，
                // 不用去猜它到底命中了哪个默认值。
                .withCacheConfiguration(CACHE_ARTICLE_PAGE, baseConfig)
                .withCacheConfiguration(CACHE_ARTICLE_DETAIL, baseConfig)
                .withCacheConfiguration(CACHE_ARTICLE_STATS, statsConfig)
                .withCacheConfiguration(CACHE_TAG_LIST, baseConfig)
                .withCacheConfiguration(CACHE_CATEGORY_LIST, baseConfig)
                .withCacheConfiguration(CACHE_FRIEND_LINK_LIST, baseConfig)
                .withCacheConfiguration(CACHE_PROJECT_LIST, baseConfig)
                .withCacheConfiguration(CACHE_FAVORITE_LIST, baseConfig)
                // 关于页是单条对象（不是列表），但 TTL 与序列化规则和列表完全一样，
                // 所以也走 baseConfig —— 没有差异就不必造一份只为了"看起来整齐"的配置
                .withCacheConfiguration(CACHE_ABOUT, baseConfig)
                // 音乐列表也是列表，TTL 与序列化规则和上面几个完全一样，所以同样走 baseConfig
                .withCacheConfiguration(CACHE_MUSIC_LIST, baseConfig)
                // 站点设置是单条对象（和 about:detail 一样），TTL 与序列化规则也没有差异
                .withCacheConfiguration(CACHE_SITE_SETTING, baseConfig)
                .withCacheConfiguration(CACHE_ARTICLE_ARCHIVE, baseConfig)
                .withCacheConfiguration(CACHE_ARTICLE_RSS, baseConfig)
                .build();
    }

    /**
     * 统计缓存的 TTL：60 秒 + 0~10 秒抖动。
     *
     * 【为什么比列表缓存短得多（5 分钟 vs 1 分钟）】
     *   两个缓存的数据"变化频率"根本不同：
     *     · 列表缓存存的是标题、摘要 —— 只有作者发文/改文时才会变，
     *       而那件事会推进版本号、缓存立刻失效。TTL 只是最后的保险，给长一点没关系。
     *     · 统计里有个"总浏览量"，它是【异步落库】的（ViewCountSyncTask 每 5 分钟一批）。
     *       落库不会推进缓存版本号（它不是"文章被写"），所以这个数字天生会滞后。
     *       TTL 定成 60 秒 = 最多滞后一分钟，用户基本察觉不到。
     *   抖动范围也刻意用 10 秒而不是 60 秒：TTL 本身才 60 秒，
     *   再来 60 秒抖动会让"1 分钟"这个承诺变得很虚。
     */
    private Duration resolveStatsTtl(Object key, Object value) {
        return TTL_STATS.plusSeconds(ThreadLocalRandom.current().nextInt(10));
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
