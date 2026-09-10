package com.yigalaxy.yiguixingtu.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * =====================================================================
 * 异步执行配置 —— 目前唯一的用武之地是"操作审计异步落库"
 *
 * 【@EnableAsync 是开关，没有它 @Async 只是注释】
 *   这一点和 @EnableCaching、@EnableScheduling 完全一样：
 *   少了它，方法上的 @Async 不报错、也不生效 —— 代码看起来完全正常，
 *   只是"异步"这件事没发生（变成同步执行，接口悄悄变慢）。
 *   本项目已经因为同一类问题踩过两次（Flyway 静默不执行、缓存注解不生效），
 *   所以凡是"开关型"的注解，都专门写一个类放它，让人一眼能看到它开着。
 *
 * 【线程池的参数不写在这里，而是在 application.properties 的
 *   spring.task.execution.* 里】
 *   那是 Spring Boot 为 @Async 准备的标准配置项（Boot 会据此自动创建
 *   一个 ThreadPoolTaskExecutor，bean 名 applicationTaskExecutor）。
 *   放配置文件而不是硬编码，好处是"调参不用改代码、不用重新打包"——
 *   将来真出现审计积压，运维改一个环境变量就能把队列调大。
 *   具体参数与理由见 application.properties 里那一段的注释。
 *
 * 【这个 Customizer 只做一件 Boot 没暴露成配置项的事：拒绝策略】
 *   spring.task.execution.* 能配核心/最大线程数、队列容量、线程名前缀，
 *   但配不了 RejectedExecutionHandler —— 而它对审计来说恰恰是关键：
 *   · 队列满了之后，默认策略是直接抛异常。而这个异常会抛到【提交任务的那个线程】，
 *     也就是刚刚提交完业务事务的请求线程 —— 结果是：
 *     用户的文章已经保存成功了，接口却因为"审计队列满了"报 500。
 *   · 换成 CallerRunsPolicy：队列满时由提交者自己执行这条插入。
 *     表现为"接口稍微慢一点"，但审计记录不会丢、请求也不会失败。
 *   → 对审计这类"记录比性能重要"的场景，把日志写下去比保住这点延迟更重要。
 *     （反过来，如果是"给用户发通知"这种任务，丢了就丢了，那时该用别的策略。）
 * =====================================================================
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public ThreadPoolTaskExecutorCustomizer auditTaskExecutorCustomizer() {
        return executor -> {
            // 这里的参数类型是 ThreadPoolTaskExecutor（不是 Executor 接口）——
            // 看过这个接口的签名确认过，所以可以直接调它的方法，不用强转也不用 instanceof。
            //
            // ⚠️ 注意它是【全局】生效的：Boot 创建的所有 ThreadPoolTaskExecutor 都会走这里。
            //    目前项目里只有 @Async 用的那一个，所以没问题；
            //    将来如果自己 new 了别的线程池，那些不受影响（它们不走 Boot 的定制器）。
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
            log.info("异步线程池已配置: 队列满时由调用线程执行（保证审计不丢）");
        };
    }
}
