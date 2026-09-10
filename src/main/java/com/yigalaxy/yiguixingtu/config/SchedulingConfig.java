package com.yigalaxy.yiguixingtu.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * =====================================================================
 * 定时任务配置
 *
 * 【这个类只有一行注解，为什么还要单独建一个文件】
 *   因为 {@code @EnableScheduling} 是个【开关】：没有它，
 *   所有 {@code @Scheduled} 方法都只是普通方法 ——
 *   不报错、不警告、也不执行（和 @EnableCaching 忘开时一模一样）。
 *   把开关单独放在一个类里，将来有人问"定时任务为什么没跑"，
 *   一眼就能看到它存在、且被显式打开。
 *
 * 【它现在启用了什么】
 *   ViewCountSyncTask.syncViewCounts() —— 每 5 分钟把 Redis 里累计的
 *   文章浏览量批量写回数据库（见该类的注释）。
 *
 * 【补充：@EnableScheduling 的线程模型】
 *   默认只用一个单线程调度器，所以所有定时任务是【串行】执行的 ——
 *   一个任务卡住会拖住其它任务。本项目目前只有浏览量同步这一个任务，
 *   单线程完全够用。将来任务多了（比如加清理任务），
 *   应该显式配一个 TaskScheduler 的线程池，而不是靠默认的。
 * =====================================================================
 */
@Slf4j
@Configuration
@EnableScheduling
public class SchedulingConfig {

    public SchedulingConfig() {
        // 启动时打一行日志，方便确认"定时任务的开关切在哪个类上"
        log.info("定时任务已启用（@EnableScheduling）");
    }
}
