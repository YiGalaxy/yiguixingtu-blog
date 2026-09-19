package com.yigalaxy.yiguixingtu.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * =====================================================================
 * 从请求里取【真实客户端 IP】—— 全项目唯一一处解析规则
 *
 * 【为什么要抽出来】这段逻辑原本在三个地方各有一份（操作审计、评论、
 *   访问日志）。三份"看起来一样"的解析，改一处忘一处是迟早的事 ——
 *   而这里改错的后果不是"少记一个字段"，是把**别人的 IP 记成你自己**。
 *   规则收在这里之后，三处调用点读起来都是"取来源 IP"。
 *
 * 【为什么优先读 X-Forwarded-For】
 *   线上请求先过宿主机的 Nginx 再到后端，此时 {@code getRemoteAddr()} 拿到的是
 *   **Nginx 自己的地址**（127.0.0.1）—— 记它没有任何意义，全部访客会变成同一个 IP。
 *   Nginx 会把真实客户端地址放进 X-Forwarded-For（见 README 的 Nginx 配置）。
 *
 * 【这个头可以被伪造 —— 为什么这里仍然信它】
 *   后端只绑回环地址、只能经由宿主机的 Nginx 进来，而 Nginx 会**覆盖**
 *   （而不是追加）这个头，所以伪造的请求到不了这里。
 *   ⚠️ 这个前提一旦变了（比如后端被直接暴露、或者前面挂了两层代理），
 *   解析方式必须跟着改成"只信任最后一跳"—— 那时这个类的注释也要一起改。
 *
 * 【为什么取第一段】X-Forwarded-For 可能是
 *   {@code "真实客户端, 代理1, 代理2"} 这样一串，第一段才是客户端。
 * =====================================================================
 */
public final class ClientIp {

    /** 与数据库里 ip 列的长度一致（comment.ip 与 visit_log.ip 都是 varchar(64)） */
    private static final int MAX_LENGTH = 64;

    /**
     * @param request 当前请求；**允许为 null**（Service 层的单测里常常不关心 IP，
     *                直接传 null 比造一个假的 HttpServletRequest 简单得多）
     * @return 客户端 IP；取不到时返回 null（不是空串 —— "没有"和"空值"要分得开）
     */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return truncate(ip);
    }

    /**
     * 超长的值截断到列长以内。
     *
     * 【为什么要截断而不是报错】值来自请求头，可以被构造成任意长。
     *   不截断的话，一个超长的头会让 {@code INSERT} 直接失败 ——
     *   而"记不下来源 IP"不值得把用户的那次评论、那次访问整个搞崩。
     */
    private static String truncate(String ip) {
        if (ip == null) {
            return null;
        }
        return ip.length() > MAX_LENGTH ? ip.substring(0, MAX_LENGTH) : ip;
    }

    /** 工具类不实例化（与项目里其它 *Patterns / *Util 一致） */
    private ClientIp() {
    }
}
