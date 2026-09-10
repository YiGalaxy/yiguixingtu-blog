package com.yigalaxy.yiguixingtu.auth.metrics;

import com.yigalaxy.yiguixingtu.common.metrics.BusinessMetrics;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * =====================================================================
 * 登录指标的采集器 —— 监听 Spring Security 自己的认证事件
 *
 * ============ 为什么是"监听事件"而不是在登录接口里写统计代码 ============
 *
 * 最直觉的写法是在 AuthController.login 里包一层 try/catch：
 *   成功 → 计数 +1；捕获到异常 → 失败计数 +1、再把异常抛出去。
 * 能跑，但有三个问题：
 *   ① 它只在"走 HTTP 接口登录"这一条路径上有效。将来加验证码登录、
 *      或者用 AuthenticationManager 在别处认证（比如后台模拟登录），那份统计就漏了；
 *   ② 异常要原样重抛，那个 catch 块本身成了"为了统计而存在"的代码，
 *      以后谁改登录逻辑都得小心别把统计碰坏；
 *   ③ 得自己判断异常类型来区分"密码错"和"账号被禁用"。
 *
 * Spring Security 在认证成功/失败时【本来就会发布事件】
 * （由 DefaultAuthenticationEventPublisher 负责把各种异常翻译成对应的事件类型），
 * 我们只要监听就行 —— 这就是"用框架的扩展点，而不是自己在业务代码里插桩"。
 *
 * ⚠️ 注意这个事件机制有前提：AuthenticationManager 上要挂一个
 * AuthenticationEventPublisher。Spring Boot 会自动配置一个
 * （{@code DefaultAuthenticationEventPublisher}），所以直接监听就能收到；
 * 但这件事不能靠"应该是这样"，所以 MetricsEndpointTest 里有一条用例
 * 真的去登录失败一次、再断言指标涨了 —— 事件没发出来的话那条用例会红。
 *
 * ============ 为什么监听的是 AbstractAuthenticationFailureEvent（抽象类）============
 *   失败事件有一整个家族，各自对应一种异常：
 *     · BadCredentialsException              → AuthenticationFailureBadCredentialsEvent（密码错）
 *     · DisabledException                    → AuthenticationFailureDisabledEvent（账号被禁用）
 *     · LockedException / AccountExpired…    → 对应的其它子类
 *     · UsernameNotFoundException 及其它     → AuthenticationFailureServiceExceptionEvent
 *   如果一个个 @EventListener 去写，既啰嗦又会漏（将来 Spring 加一个新事件类型，
 *   你就少统计一类）。监听抽象基类，等于"这一类事件我全都要"。
 *
 * ============ 这个类不做什么 ============
 *   · 不记录用户名（无界基数 + 个人信息，只该进日志）
 *   · 不记录密码的任何信息
 *   · 不改动认证结果 —— 它只是旁观者，抛不抛异常、返回什么，全都不受它影响
 * =====================================================================
 */
@Component
public class AuthenticationMetricsListener {

    private final BusinessMetrics metrics;

    public AuthenticationMetricsListener(BusinessMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 认证成功。
     *
     * 【注意它会被不止一次触发吗】
     *   不会。DaoAuthenticationProvider 认证成功时发布一次；
     *   我们这里没有"多次认证"的场景（没有 remember-me、没有 OAuth 二次换票）。
     *   将来如果加了别的认证方式，要注意这里统计的是"认证动作次数"，
     *   不是"登录用户数" —— 两者在有多种认证方式时会不相等。
     */
    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        metrics.recordLoginSuccess();
    }

    /**
     * 认证失败（覆盖密码错、账号禁用、用户不存在等全部情形）。
     *
     * 【为什么标签用 【异常类名】 而不是异常消息】
     *   异常消息里可能带上用户输入（"用户名或密码错误: admin"），
     *   做成标签同样是无界基数，而且会把用户输入带到监控系统里去。
     *   类名是一个封闭的小集合，正好适合当标签。
     */
    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        metrics.recordLoginFailure(event.getException().getClass().getSimpleName());
    }
}
