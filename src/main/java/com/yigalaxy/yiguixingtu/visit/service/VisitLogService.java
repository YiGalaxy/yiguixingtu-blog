package com.yigalaxy.yiguixingtu.visit.service;

import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.visit.dto.VisitStatsVO;
import com.yigalaxy.yiguixingtu.visit.dto.VisitorIpVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 访问日志：记录与统计。
 */
public interface VisitLogService {

    /**
     * 记一条访问。
     *
     * 【什么时候调用】前端在**每次路由变化**时上报一次（见 web 端的 app.vue）。
     *   ⚠️ 它记的是**页面访问**，不是 API 请求 —— 后端没法从自己的日志里
     *   看出"用户翻了几个页面"（SPA 站内跳转根本不经过后端）。
     *
     * 【为什么这个方法不抛异常】统计是**锦上添花**的东西：
     *   记不上一条访问，绝不能影响用户看页面。所以调用方（Controller）会兜住
     *   任何异常只记日志 —— 见 VisitController 里的说明。
     *
     * @param rawPath     前端报上来的路径（可能带查询串，实现里会清掉）
     * @param request     取 IP / Referer / User-Agent 用；允许为 null（单测）
     * @param currentUser 当前登录用户；未登录为 null
     */
    void record(String rawPath, HttpServletRequest request, LoginUser currentUser);

    /**
     * 统计报表（后台用）。
     *
     * @param days 看最近多少天（含今天）；超出允许范围时会被夹到边界，见实现
     */
    VisitStatsVO stats(int days);

    /**
     * 访客 IP 列表（后台用，统计页下钻出来的那一层）。
     *
     * 【它和 stats 是什么关系】stats 回答"这段时间怎么样"（曲线、构成、排行），
     *   这个回答"都是谁" —— 同一个区间的另一个切面。
     *   所以两者的 days 走的是**同一个夹取与起点计算**（见实现里的 sinceOf），
     *   否则会出现"曲线按 30 天算、IP 列表按别的口径"这种对不上的情况。
     *
     * @param days 看最近多少天（含今天）；超出允许范围时会被夹到边界
     * @return 按 IP 聚合后的访客，访问次数多的在前；结果条数有上限（见实现）
     */
    List<VisitorIpVO> visitorIps(int days);
}
