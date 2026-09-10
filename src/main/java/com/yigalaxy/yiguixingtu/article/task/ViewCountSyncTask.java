package com.yigalaxy.yiguixingtu.article.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yigalaxy.yiguixingtu.article.cache.ArticleViewCounter;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * =====================================================================
 * 浏览量定时落库任务 —— 把 Redis 里累计的增量批量写回 MySQL
 *
 * 【为什么要有它】
 *   详情页现在只 INCR Redis（见 {@link ArticleViewCounter}），
 *   但 Redis 里的数字不能当最终数据：它可能被清、也没法用来排序和统计。
 *   所以要定期落库。这个任务就是那个"定期"。
 *
 * 【为什么是"定时批量"而不是"每次访问都落库"】
 *   每次访问都落库 = 回到原来的问题（读接口带写操作、行锁竞争）。
 *   合并成"每 5 分钟一次、一次写多篇"之后：
 *     · 写库次数从"访问量"降到"5 分钟一批"
 *     · 同一篇文章在这 5 分钟内的 N 次访问合并成一条 UPDATE
 *
 * 【用到的东西】
 *   · {@code @Scheduled} —— Spring 自带的定时任务。
 *     用 {@code fixedDelay}（上一次执行【结束】后再等这么久）而不是 {@code fixedRate}
 *     （每 N 毫秒触发一次，不管上次跑完没有）：后者在任务执行时间超过间隔时
 *     会积压、甚至重复执行。fixedDelay 天生不会重入。
 *   · {@code @EnableScheduling} —— 开关。没有它 @Scheduled 只是一个注释（见 SchedulingConfig）
 *   · {@code @Transactional} —— 一批更新要么都成、要么都不成。
 *     ⚠️ 注意它【挡不住】"取了增量但写库失败"这种情况，见下面"一个诚实的说明"
 *
 * 【⚠️ 一个诚实的说明：这里的失败处理是"丢了就丢了"】
 *   流程是"先从 Redis 取走增量（GETDEL，数据已经不在 Redis 了）→ 再写库"。
 *   如果写库这一步失败，那批增量就永久丢了。
 *   为什么接受这个代价：
 *     · 浏览量是展示型数据，少几次不会造成任何业务错误
 *     · 反过来做（先写库成功再删 Redis）会让同一批数字被重复累加 ——
 *       而"数字偏大"比"数字偏小"更容易被人当成作弊
 *   更严格的方案是"用消息队列做可靠投递"，那是另一个量级的复杂度，
 *   对浏览量这个精度要求不值得。这里选择简单且不会重复计数的那一种。
 * =====================================================================
 */
@Slf4j
@Component
public class ViewCountSyncTask {

    private final ArticleViewCounter viewCounter;
    private final ArticleMapper articleMapper;

    public ViewCountSyncTask(ArticleViewCounter viewCounter, ArticleMapper articleMapper) {
        this.viewCounter = viewCounter;
        this.articleMapper = articleMapper;
    }

    /**
     * 定时同步。
     *
     * 间隔由配置 {@code app.article.view-sync-interval-ms} 决定，默认 5 分钟。
     * 抽成配置是为了"能调"：压测或排查时想让它立刻跑一次，改配置比改代码快。
     *
     * 【为什么这个方法要 public 且不返回 void 之外的东西】
     *   测试里会直接调用它（而不是等 5 分钟）——
     *   定时任务本身由框架保证触发，但"同步逻辑对不对"应该能被确定性地验证。
     */
    @Scheduled(fixedDelayString = "${app.article.view-sync-interval-ms:300000}")
    @Transactional(rollbackFor = Exception.class)
    public void syncViewCounts() {
        Map<Long, Long> deltas = viewCounter.drainAll();

        if (deltas.isEmpty()) {
            // 没有增量就什么都不做。日志也不打 —— 每 5 分钟一条"无事发生"
            // 会把真正的信息淹掉（5 分钟一条，一天 288 条）
            return;
        }

        // 逐篇累加。
        //
        // 【为什么用 SQL 里的 view_count = view_count + ? 而不是"查出来加完再写回"】
        //   后者在并发下会丢计数：两个线程同时读到 100，各自加完都写 101。
        //   让数据库自己执行"在原值上加"，它天然是原子的。
        //   这和原来 increaseViewCount 里的做法保持同一个正确思路，
        //   只是现在从"每次访问一次"变成了"每 5 分钟一批"。
        int updated = 0;
        List<Long> failed = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : deltas.entrySet()) {
            Long articleId = entry.getKey();
            Long delta = entry.getValue();
            try {
                updated += articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                        .eq(Article::getId, articleId)
                        .setSql("view_count = view_count + " + delta));
            } catch (Exception e) {
                failed.add(articleId);
                log.warn("同步浏览量失败, articleId={}, delta={}: {}", articleId, delta, e.getMessage());
            }
        }

        log.info("浏览量同步完成: 本次处理 {} 篇文章的增量, 影响 {} 行{}",
                deltas.size(), updated,
                failed.isEmpty() ? "" : ", 失败 " + failed.size() + " 篇: " + failed);
    }
}
