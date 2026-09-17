package com.yigalaxy.yiguixingtu.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * =====================================================================
 * 「站点内容」缓存的版本号 —— 给 F5 的四个内容模块（友链 / 项目 / 收藏 / 关于）
 * 共用，用来一键作废它们的公开只读缓存
 *
 * ============ 它和 ArticleCacheVersion 是什么关系 ============
 *
 * 【同一套机制，不是第二套写法】
 *   项目里"缓存失效"只有一种做法（见 {@code article/cache/ArticleCacheVersion}）：
 *   Redis 里放一个字符串计数器，缓存 key 里带上它的当前值，
 *   写操作只做一次 INCR —— 版本号一变，老 key 就再也拼不出来，等于瞬间全部失效。
 *   这个类就是同一套东西，只是换了计数器的名字和归属。
 *
 * 【为什么不直接复用 ArticleCacheVersion】
 *   标签订阅了文章那个版本号，是因为两者【真的相关】：标签列表里带着
 *   "每个标签下有几篇已发布文章"，文章一写这个数就变了 ——
 *   共用版本号是"内容真的会一起变"，不是图省事。
 *   而友链 / 项目 / 收藏 / 关于和文章【一点关系都没有】：
 *   如果让"加一条友链"去推进 {@code article:page:version}，
 *   后果是文章列表、详情、归档、RSS、分类、标签六份缓存全部被白白作废 ——
 *   每次改友链都会让首页重新查一遍库。
 *   功能不会错（多查一次库而已），但这是"用一个机制去覆盖它管不着的东西"，
 *   排查时还会让人以为"文章那边的缓存为什么老是重建"。
 *   ⇒ 所以给这一族内容单独一个计数器：**谁的数据变，就推进谁的版本号**。
 *
 * 【四个模块共用【一个】计数器，而不是四个】
 *   它们都满足同一个形状：站点级静态内容、管理员人工维护、写极少读很多、
 *   每个模块只有一个"全量列表"（关于是单条）。这样一个计数器就够，
 *   代价只是"改一条友链会让项目列表的缓存也重建一次"——
 *   这类内容一个月都未必改一次，重建的代价可以忽略；
 *   换成四个计数器则是四份要维护、要清理、要写测试的状态。
 *   （和"分类与标签共用文章版本号"是同一个取舍：宁可多作废一次，也不多养一套状态。）
 *
 * ============ 用到的东西 ============
 *   · {@code @Component} —— 交给容器管理：既能被 Service 注入，
 *     也能在 {@code @Cacheable} 的 SpEL 里用 {@code @contentCacheVersion.current()} 引用
 *     （bean 名由 Spring 按"类名首字母小写"推导）
 *   · {@link StringRedisTemplate} —— 版本号就是一个普通字符串计数器
 *   · {@code opsForValue().increment(key)} —— 对应 Redis 的 INCR：
 *     单条原子命令、同步返回，所以"写完立刻读一定读到新数据"是有保证的；
 *     key 不存在时自动从 0 开始，不需要初始化
 *
 * ============ ⚠️ 为什么版本号必须放在 Redis，不能是内存里的 AtomicLong ============
 *   内存版只在单实例下正确：部署两个后端实例时，A 实例改友链只让自己内存里的
 *   版本号 +1，B 实例仍然用老版本号拼 key、继续返回旧列表。
 *   放 Redis 才能让所有实例看到同一个版本号。
 * =====================================================================
 */
@Slf4j
@Component
public class ContentCacheVersion {

    /**
     * 四个内容模块共用的版本号在 Redis 里的 key。
     *
     * 【为什么名字里是 content 而不是 link】
     *   因为它不只属于友链 —— 项目 / 收藏 / 关于的写操作推进的也是它。
     *   取一个模块名会让后来的人以为"这里只能管友链"。
     */
    static final String VERSION_KEY = "content:list:version";

    /** Redis 里还没有这个 key 时的版本号（和 ArticleCacheVersion 保持一致：从 "0" 开始） */
    private static final String INITIAL_VERSION = "0";

    private final StringRedisTemplate redis;

    public ContentCacheVersion(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 取当前版本号，供拼装缓存 key 使用。
     *
     * 【为什么要现取一次、而不是启动时读一次缓存起来】
     *   缓存 key 是在【每次读写缓存时】临时拼出来的；如果这里返回一个进程内的旧快照，
     *   别的实例推进过的版本号就看不到，于是"改了内容前台不更新"。
     *   每次读一次 Redis 的代价是一次 GET，相对于后面那次查库可以忽略。
     *
     * @return 当前版本号字符串；Redis 里还没有时返回 "0"
     */
    public String current() {
        try {
            String v = redis.opsForValue().get(VERSION_KEY);
            return v == null ? INITIAL_VERSION : v;
        } catch (Exception e) {
            // 【为什么这里必须自己兜住异常 —— 连 CacheErrorHandler 都指望不上】
            //   完整推导（含 spring-context 源码行号）写在
            //   ArticleCacheVersion.read 的注释里，一句话概括：
            //   本方法是在 @Cacheable 的 SpEL key 表达式里被调用的，而 SpEL 求值
            //   发生在 CacheAspectSupport.generateKey() 里、【在 try/catch 之外】——
            //   异常会一路冒到接口层变成 500，而不是"缓存失效、回落查库"。
            //   中招的是友链 / 项目 / 收藏 / 关于 / 音乐 / 站点设置这一整组缓存接口。
            //
            //   返回一个每次都不一样的值 ⇒ 拼出的 key 永不命中 ⇒ 自动降级为查库。
            //   （为什么不能返回固定的 "0"：那会让故障期间所有请求去命中同一个 key，
            //    而那个 key 里可能存着旧数据，表现是"缓存坏了但看起来一切正常"。）
            log.warn("读取内容缓存版本号失败, key={} —— 本次返回一个不重复的值，缓存自动降级为查库: {}",
                    VERSION_KEY, e.getMessage());
            return "unavailable-" + UUID.randomUUID();
        }
    }

    /**
     * 内容被写（新建 / 编辑 / 删除 / 关于的保存）之后调用。
     *
     * 【调用时机：业务写操作【成功之后】】
     *   如果写操作最终抛异常回滚了，却先把版本号推进了，后果只是
     *   "缓存被白白作废一次、下次重新查库" —— 多一次查询，不会产生错误数据。
     *   反过来（写库成功却忘了推进版本号）才会留下脏缓存，前台一直显示旧内容。
     *   所以这里的取向是"宁可多作废一次，也不能漏"。
     *   （每个写方法末尾都有一个 bump()，测试里各有一条用例盯着，见 FriendLinkTest ⑬）
     *
     * @return 自增之后的版本号（便于日志排查，测试也用它断言）
     */
    public long bump() {
        Long next = redis.opsForValue().increment(VERSION_KEY);
        // increment 正常不会返回 null（key 不存在时从 0 开始）。
        // 这里兜一下是为了避免拆箱 NPE：宁可抛一个说清楚原因的异常，
        // 也不要让一个莫名其妙的 NullPointerException 出现在业务堆栈里
        if (next == null) {
            throw new IllegalStateException("推进内容缓存版本号失败，Redis 未返回结果: " + VERSION_KEY);
        }
        return next;
    }
}
