package com.yigalaxy.yiguixingtu.audit;

import com.yigalaxy.yiguixingtu.auth.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * =====================================================================
 * 操作审计的"记录入口" —— 业务代码只调它一行，剩下的交给事件机制
 *
 * 【它在整条链路里的位置】
 *
 *   Service（@Transactional 的写方法）
 *        │  record(...)                     ← 只加一行，不关心后面怎么处理
 *        ▼
 *   本类：顺手把"当前是谁 / 从哪来 / 这次请求的 traceId"抄下来
 *        │  publishEvent(OperationLogEvent)
 *        ▼
 *   OperationLogListener（@Async + AFTER_COMMIT）
 *        │  业务真的提交了才落到 operation_log 表
 *        ▼
 *   异步线程插库，请求早就返回了
 *
 * 【为什么"抄上下文"这件事放在这里，而不是让监听器自己取】
 *   因为监听器跑在别的线程上，而 SecurityContext / MDC / RequestContextHolder
 *   都是 ThreadLocal —— 换线程就取不到了。必须在【发布事件的这一刻】
 *   （也就是还在请求线程上时）把它们复制进事件对象。
 *
 * 【它为什么不是 @Service 而是 @Component】
 *   它不承载业务语义，只是一个基础设施组件；
 *   命名上放 audit 包、叫 Recorder，也是为了让业务代码里那一行读起来是
 *   "记一笔审计"，而不是"调个服务"。
 * =====================================================================
 */
@Slf4j
@Component
public class OperationLogRecorder {

    private final ApplicationEventPublisher eventPublisher;

    public OperationLogRecorder(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 记一笔操作审计。
     *
     * 【调用时机】放在业务写操作【成功之后】、且与它在同一个事务里。
     *   放成功之后：失败了就不该有"成功"的记录。
     *   在同一事务里：这样 AFTER_COMMIT 才有意义 ——
     *   事务回滚时这条事件根本不会触发监听器，
     *   保证"没真正发生的事不会被记下来"。
     *
     * @param action   操作类型
     * @param target   操作对象类型
     * @param targetId 操作对象ID（新建时可能就是刚生成的那个 id）
     * @param detail   补充说明，可为 null
     */
    public void record(OperationAction action, AuditTarget target, Long targetId, String detail) {
        Long userId = null;
        String username = null;
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            // 【为什么要判断类型】匿名请求时 principal 是字符串 "anonymousUser"，
            // 直接强转成 LoginUser 会抛 ClassCastException ——
            // 那会让一次正常的写操作因为"记审计"而失败，本末倒置
            if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
                userId = loginUser.getUser().getId();
                username = loginUser.getUser().getUsername();
            }
        } catch (Exception e) {
            // 取不到用户不该影响业务，最多是这条审计记录缺了操作人
            log.warn("记录操作审计时读取当前用户失败（该条记录的操作人将为空）: {}", e.getMessage());
        }

        // traceId 从 MDC 取 —— 它由 5.1 的链路追踪写进去，
        // 是审计表和日志系统之间的那根线
        String traceId = MDC.get("traceId");

        eventPublisher.publishEvent(new OperationLogEvent(
                userId, username, action, target, targetId, detail, currentIp(), traceId));
    }

    /**
     * 取来源 IP。
     *
     * 【为什么优先读 X-Forwarded-For】
     *   线上请求先过 Nginx 再到后端，此时 request.getRemoteAddr() 拿到的是
     *   Nginx 的地址（127.0.0.1），记它没有任何意义。
     *   Nginx 会把真实客户端地址放进 X-Forwarded-For（见 README 的 Nginx 配置）。
     *
     * 【读 X-Forwarded-For 安全吗 —— 安全，而且理由很具体】
     *   这个头是客户端可以自己伪造的，正常情况下不该信。
     *   但本项目后端端口【只绑在 127.0.0.1】（见 docker-compose.prod.yaml），
     *   公网连不到它，只能经由宿主机的 Nginx 进来 ——
     *   而 Nginx 会用 $proxy_add_x_forwarded_for 覆盖这个头。
     *   也就是说：伪造的请求根本到不了这里。
     *   ⚠️ 一旦有人把后端端口暴露到公网，这个假设就不成立了 ——
     *      那时 X-Forwarded-For 必须改成"只信任最后一跳"的解析方式。
     *
     * 【为什么取第一段】
     *   X-Forwarded-For 可能是 "客户端IP, 代理1, 代理2" 这样一串，
     *   第一段才是最初的客户端。
     *
     * 【长度为什么要截断】表里是 varchar(64)，超长的头直接写会报错，
     *   而审计记录不值得为它把整个业务带崩 —— 截断并记下来更合理。
     */
    private String currentIp() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
                return null;
            }
            HttpServletRequest request = servletAttributes.getRequest();

            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].trim();
                return truncate(first);
            }
            return truncate(request.getRemoteAddr());
        } catch (Exception e) {
            log.warn("记录操作审计时读取来源IP失败（该条记录的IP将为空）: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String ip) {
        if (ip == null || ip.length() <= 64) {
            return ip;
        }
        return ip.substring(0, 64);
    }
}
