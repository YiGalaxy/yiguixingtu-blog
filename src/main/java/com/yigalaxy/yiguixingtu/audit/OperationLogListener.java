package com.yigalaxy.yiguixingtu.audit;

import com.yigalaxy.yiguixingtu.audit.mapper.OperationLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * =====================================================================
 * 操作审计的落库监听器 —— 业务提交之后、在另一个线程上把记录写进表
 *
 * 【这个类只有两个注解，但每一个都在解决一个具体问题】
 *
 * ── ① @TransactionalEventListener(phase = AFTER_COMMIT) ──
 *   "业务事务【真的提交了】才落库"。
 *   这一条直接决定了审计数据的可信度：
 *     · 业务最后回滚了（比如保存文章时分类不存在）→ 事件被丢弃 → 不记账。
 *       记下来的话，表里会留下一堆"从没发生过"的操作
 *     · 事务还没提交就落库 → 可能出现"审计说有、业务库里没有"的鬼记录
 *
 *   ⚠️ 【fallbackExecution = true 这一项很重要，它解决一个很容易漏的场景】
 *     默认值是 false，意思是"没有事务在跑的时候，这个监听器【不执行】"。
 *     而本项目的 UserServiceImpl.updateStatus / updateRole / resetPassword
 *     三个方法【没有 @Transactional】（这是已知的风格不一致，见 README
 *     「接口安全约定」），在那里发的事件如果没有 fallback，会被【静默丢弃】——
 *     审计功能对这三个接口等于没生效，而且不会有任何报错。
 *     加上 fallbackExecution = true 之后：有事务就等提交，没事务就立刻执行，
 *     两种情形都能记上。
 *     （这几个方法之所以没补 @Transactional，见 README 里"为什么没顺手补上"
 *      的说明：补上会让"清缓存"发生在提交之前，反而扩大了一致性窗口。）
 *
 * ── ② @Async ──
 *   "落库在另一个线程做，请求不等它"。
 *   审计是一次额外的数据库写入，让用户的上传/保存动作等它做完毫无道理。
 *   ⚠️ 但正因为换了线程，事件对象里必须【已经】带上用户、IP、traceId
 *      （那三样都是 ThreadLocal，新线程里取不到）—— 见 OperationLogRecorder。
 *
 * ── ③ @EnableAsync 在哪 ──
 *   见 config/AsyncConfig：这两个注解里只有 @Async 需要一个开关，
 *   没有 @EnableAsync 的话 @Async 只是个注释 —— 不报错、也不生效
 *   （和 @EnableCaching / @EnableScheduling 是同一类坑）。
 *
 * 【失败怎么办：记日志，不往上抛】
 *   监听器已经是链路的末端了（业务早就提交并返回），异常往上抛没有接收方，
 *   只会变成一条 ERROR 日志。所以这里自己 catch 住、打印清楚，
 *   保持"审计失败不影响业务"这个边界。
 *   ⚠️ 代价说清楚：极端情况下（数据库挂了）会有审计记录丢失。
 *   对个人博客来说这是可接受的取舍；如果是金融场景，
 *   那就该走"消息队列 + 落库确认"来保证不丢，而不是靠这里的 catch。
 * =====================================================================
 */
@Slf4j
@Component
public class OperationLogListener {

    private final OperationLogMapper operationLogMapper;

    public OperationLogListener(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 业务提交之后，异步落库。
     *
     * 【create_time 为什么显式赋值，而不是靠数据库默认值】
     *   表上的 DEFAULT CURRENT_TIMESTAMP 也能用，但那样时间是由【数据库】决定的，
     *   而这条记录代表的是"用户什么时候做的操作"。两者在正常情况下只差毫秒，
     *   但显式写下来更明确，也方便将来排查时钟/时区问题时对照。
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOperationCommitted(OperationLogEvent event) {
        try {
            OperationLog record = new OperationLog();
            record.setUserId(event.userId());
            record.setUsername(event.username());
            record.setAction(event.action().name());
            record.setTargetType(event.target() == null ? null : event.target().name());
            record.setTargetId(event.targetId());
            record.setDetail(event.detail());
            record.setIp(event.ip());
            record.setTraceId(event.traceId());
            record.setCreateTime(java.time.LocalDateTime.now());

            operationLogMapper.insert(record);

            log.info("已记录操作审计: {} {} targetId={} operator={} traceId={}",
                    event.action().name(), event.target(), event.targetId(),
                    event.username(), event.traceId());
        } catch (Exception e) {
            // 见类注释的说明：审计失败绝不能影响已经成功的业务，
            // 所以这里吞掉异常，但要把信息打全，方便人工补录或排查
            log.error("写入操作审计失败（业务已成功，仅审计缺失）: action={}, targetId={}, operator={}",
                    event.action(), event.targetId(), event.username(), e);
        }
    }
}
