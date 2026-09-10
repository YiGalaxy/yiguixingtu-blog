package com.yigalaxy.yiguixingtu.audit;

import java.io.Serializable;

/**
 * 操作审计事件 —— "有人做了一件需要留痕的事"。
 *
 * ============ 为什么用事件，而不是在业务方法里直接插库 ============
 *
 * 最直白的写法是在每个写方法末尾直接调 mapper.insert(...)。那样有三个问题：
 *   ① 【业务方法要为审计负责】—— 审计是横切关注点，塞进业务代码之后，
 *      以后每加一个写方法都得记得加一行，漏了也没人发现
 *   ② 【事务语义不对】—— 直接插库会落在业务的事务里：
 *      业务最后回滚了，审计记录也跟着没了（"没发生的事"不该被记），
 *      但如果业务已经做完、插库却失败，业务又要跟着回滚 ——
 *      一次审计写入的失败不该把用户的正常操作搞挂
 *   ③ 【拖慢接口】—— 每次写操作都同步多一次数据库往返
 *
 * 用 Spring 的事件机制把这三件事一次解决：
 *   业务只负责"喊一声"（publishEvent），**不关心谁在处理、什么时候处理**；
 *   监听器上用 {@code @TransactionalEventListener(AFTER_COMMIT)} 保证
 *   "业务真的提交了才记"，用 {@code @Async} 把落库挪到别的线程。
 *
 * ============ ⚠️ 为什么用户信息和 traceId 要"在事件里带上"，而不是等异步线程自己取 ============
 *   事件是在【请求线程】上发布的，那时 SecurityContext（当前登录用户）、
 *   MDC（traceId）、RequestContextHolder（请求 IP）都还在；
 *   而监听器跑在【另一个线程】上 —— 那三样东西都是 ThreadLocal，
 *   到了新线程里全是空的。
 *   所以必须在发布的那一刻把它们【抄进事件对象】。
 *   这个坑不注意的话，审计表里 user_id 和 trace_id 会全是 null，
 *   而且不会有任何报错。
 *
 * ============ 为什么是 record ============
 *   它天然不可变，正好适合"事件"这种"发生即定稿"的数据：
 *   谁也不能在处理过程中把操作人改掉。
 *   同时还实现了 {@link Serializable} —— 不是现在需要（同步事件不走网络），
 *   而是留个余地：将来要把事件发到消息队列做异步审计时，不用再改结构。
 * =====================================================================
 */
public record OperationLogEvent(
        Long userId,
        String username,
        OperationAction action,
        AuditTarget target,
        Long targetId,
        String detail,
        String ip,
        String traceId) implements Serializable {
}
