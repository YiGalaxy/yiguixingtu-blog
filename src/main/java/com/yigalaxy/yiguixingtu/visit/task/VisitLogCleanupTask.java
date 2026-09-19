package com.yigalaxy.yiguixingtu.visit.task;

import com.yigalaxy.yiguixingtu.visit.mapper.VisitLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * =====================================================================
 * 访问日志的保留期清理 —— 每天删掉过期的记录
 *
 * 【为什么这个任务不是"可选的优化"，而是功能的一部分】
 *   这张表存的是访客的 **IP 地址（个人信息）**。没有清理任务的话，
 *   它就是一个**无限增长的、永久的**访问者档案 —— 那与"看一下访问趋势"
 *   这个用途完全不成比例。保留期是这个功能得以存在的前提，
 *   所以它有一个默认值（90 天）、可配置，并且默认是**开着**的。
 *
 * 【为什么是 90 天】它要能覆盖后台报表能查的最长跨度
 *   （AdminVisitController 把 days 夹在 [1, 90]）——
 *   保留期比报表跨度短的话，会看到"最近 30 天"的曲线在某个日期之后突然断掉，
 *   而原因是数据被自己删了。两者必须是同一组数字，改一个就要改另一个。
 *
 * 【为什么用 DELETE 而不是标记删除】保留期的目的就是让数据真的消失。
 *   标记删除在这里等于"假装删了"，个人信息还躺在表里。
 *
 * 【为什么每天只跑一次】清理不是时效性任务：晚几个小时删掉 91 天前的记录
 *   没有任何影响。跑得太勤反而会让这张表（唯一被清理的表）频繁锁行。
 *   凌晨 4 点执行：那时访问量最低，且避开了其它定时任务
 *   （浏览量落库是每 5 分钟一次，与它不冲突）。
 * =====================================================================
 */
@Slf4j
@Component
public class VisitLogCleanupTask {

    /**
     * 保留天数。
     *
     * 【为什么给一个可配置项而不是写死】不同的人对"留多久"的答案不一样，
     *   而这是一个**涉及个人信息**的决定 —— 应当由站长自己定，
     *   而不是由代码替他决定。
     */
    @Value("${app.visit.retention-days:90}")
    private int retentionDays;

    private final VisitLogMapper visitLogMapper;

    public VisitLogCleanupTask(VisitLogMapper visitLogMapper) {
        this.visitLogMapper = visitLogMapper;
    }

    /**
     * 每天凌晨 4 点执行：删掉 {@code retentionDays} 天之前的访问记录。
     *
     * ⚠️ 【它是项目里唯一一个"真正删数据"的定时任务】
     *   别的定时任务都是把 Redis 里的增量落库（ViewCountSyncTask），
     *   只有这个是不可逆的删除 —— 所以它的日志里必须把"删了多少行"写清楚，
     *   让站长能确认保留期真的在生效（而不是静默地什么都不做）。
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanup() {
        // 配置成 0 或负数时【不清理】还是【全清】？
        // 这里选了"跳过并警告"：一个配错的值（比如手滑填了 0）把全部历史一次清空，
        // 而历史是回不来的。宁可保留着，让人看到警告再去改配置。
        if (retentionDays <= 0) {
            log.warn("访问日志保留天数配置为 {}（应当 > 0），本次跳过清理。"
                    + "如果确实不想保留访问日志，应当关掉上报而不是靠这个值", retentionDays);
            return;
        }

        LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);
        int deleted = visitLogMapper.deleteBefore(before);

        if (deleted > 0) {
            log.info("访问日志清理完成: 删除了 {} 天（{}）之前的 {} 条记录",
                    retentionDays, before, deleted);
        } else {
            // 【为什么要打这一行】"什么都没删"有两种可能：确实没有过期数据，
            // 或者清理坏掉了。打一行 INFO 让站长能从日志里区分这两者
            //（每 90 天里会看到 89 次这个"空转"，但那是正常的）
            log.info("访问日志清理: 没有需要清理的记录（保留 {} 天）", retentionDays);
        }
    }
}
