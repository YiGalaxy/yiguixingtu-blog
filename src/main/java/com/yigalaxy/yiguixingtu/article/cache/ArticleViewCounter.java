package com.yigalaxy.yiguixingtu.article.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * =====================================================================
 * 文章浏览量的 Redis 计数器 —— 把"读接口里的写操作"挪出请求链路
 *
 * ============ 它解决什么问题 ============
 *
 * 在这之前，每次打开文章详情页都会执行一次
 *     UPDATE article SET view_count = view_count + 1 WHERE id = ?
 * 这会带来两个具体问题：
 *   ① 【写放大】详情页是典型的"读多写极少"接口，却是全站唯一会写库的读接口。
 *      热门文章被频繁打开时，这条 UPDATE 会成为最热的写语句
 *   ② 【行锁竞争】同一篇文章被并发打开时，这些 UPDATE 会在同一行上排队等锁。
 *      访问量越大，每个请求等锁的时间越长 —— 一个"看文章"的动作被写操作拖慢
 *
 * 现在改成：详情页只做一次 Redis 的 INCR（内存操作，微秒级、无行锁），
 * 由一个定时任务每 5 分钟把累计的增量批量写回数据库。
 *
 * ============ 为什么返回给前端的数字是"库里的值 + Redis 增量" ============
 *   库里的 view_count 是"上次同步时的快照"，最新的那些还没落库。
 *   如果只返回库里的值，用户会看到"我刷新了但数字不动"；
 *   所以要合并，见 {@link ArticleService}…准确说是 ArticleServiceImpl 里的用法。
 *   这也是"异步计数"必须付的代价：读取时要多合一次。
 *
 * ============ 用到的东西 ============
 *   · {@code INCR}（{@code opsForValue().increment}）——
 *     Redis 的单条原子命令。原子性很关键：多个请求同时打开同一篇文章，
 *     计数不会丢（这和原来用 SQL 自增而不是"读出来加一写回去"是同一个道理）
 *   · {@code GETDEL}（{@code opsForValue().getAndDelete}）——
 *     "取值并删除"是一条原子命令。用它把增量"取走"，
 *     避免"取完还没删掉时又有新访问进来，结果被一起删了"这种丢计数
 *   · {@code keys(pattern)} —— 找出所有还在累计的计数器。
 *     ⚠️ 这里说清楚：KEYS 会阻塞 Redis（它要扫整个 keyspace）。
 *     本项目的计数器 key 数量是"文章数"量级（几百条），开销可忽略，
 *     而且这个任务 5 分钟才跑一次。若将来文章到十万级，应换成 SCAN 游标分批扫。
 *     —— 现在用 KEYS 是权衡后的选择，不是不知道它的代价。
 * =====================================================================
 */
@Slf4j
@Component
public class ArticleViewCounter {

    /** Redis key 前缀，配上文章 ID 组成完整 key，如 article:view:123 */
    private static final String KEY_PREFIX = "article:view:";

    private final StringRedisTemplate redis;

    public ArticleViewCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(Long articleId) {
        return KEY_PREFIX + articleId;
    }

    /**
     * 浏览量 +1，并返回【自增之后的累计增量】。
     *
     * 【为什么要返回这个值，而不是像以前那样丢掉】
     *   调用方（浏览上报接口 ArticleServiceImpl.recordView）要立刻回一个
     *   "含本次访问的最新总数"给前端，而那个数 = 库里的快照 + 这个增量。
     *   INCR 本来就返回自增后的值，直接用它就够 ——
     *   原来丢掉返回值、紧接着再调一次 {@link #pending} 去 GET 同一个 key，
     *   是白白多一次 Redis 往返。
     *
     * @param articleId 文章 ID
     * @return 自增后的累计增量；Redis 出问题时返回 -1
     *         （调用方据此回退到 {@link #pending} 或不更新显示）
     */
    public long increment(Long articleId) {
        if (articleId == null) {
            return -1L;
        }
        try {
            Long v = redis.opsForValue().increment(key(articleId));
            return v == null ? -1L : v;
        } catch (Exception e) {
            // 【为什么这里只打日志不抛异常】浏览量是"锦上添花"的数据，
            // 它统计失败绝不该导致用户看不了文章 —— 那是拿次要功能换主要功能。
            // 代价是这次访问没被计数，可在日志里看到。
            log.warn("浏览量计数失败, articleId={}（不影响文章正常展示）: {}", articleId, e.getMessage());
            return -1L;
        }
    }

    /**
     * 取某篇文章"还没落库"的增量。
     *
     * @return 增量；没有累计到任何数时返回 0
     */
    public long pending(Long articleId) {
        if (articleId == null) {
            return 0L;
        }
        try {
            String v = redis.opsForValue().get(key(articleId));
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            // 读不到就当没有增量：展示上少算一点，总比整个详情页报错好
            log.warn("读取浏览量增量失败, articleId={}, 本次按 0 处理: {}", articleId, e.getMessage());
            return 0L;
        }
    }

    /**
     * 把【所有】文章还没落库的增量取走（取值 + 删除，原子完成）。
     *
     * 【为什么用 GETDEL 而不是"先 GET 再 DEL"】
     *   分两步的话中间有窗口：刚好在这两步之间来了新访问，
     *   那个 +1 会被后面的 DEL 一起删掉 —— 计数凭空少一次。
     *   而且这种丢失是随机的、事后无法察觉。GETDEL 把两步合成一条原子命令，没有窗口。
     *
     * @return 文章 ID → 本次需要落库的增量（只包含增量大于 0 的）
     */
    public Map<Long, Long> drainAll() {
        Map<Long, Long> deltas = new HashMap<>();
        Set<String> keys;
        try {
            keys = redis.keys(KEY_PREFIX + "*");
        } catch (Exception e) {
            log.error("扫描浏览量计数器失败，本次跳过同步", e);
            return deltas;
        }
        if (keys == null || keys.isEmpty()) {
            return deltas;
        }

        for (String k : keys) {
            try {
                Long articleId = Long.valueOf(k.substring(KEY_PREFIX.length()));
                String value = redis.opsForValue().getAndDelete(k);
                if (value != null) {
                    long delta = Long.parseLong(value);
                    if (delta > 0) {
                        deltas.put(articleId, delta);
                    }
                }
            } catch (Exception e) {
                // 单个 key 出问题不该影响其它文章：记下来继续处理下一个
                log.warn("处理浏览量计数器失败, key={}: {}", k, e.getMessage());
            }
        }
        return deltas;
    }
}
