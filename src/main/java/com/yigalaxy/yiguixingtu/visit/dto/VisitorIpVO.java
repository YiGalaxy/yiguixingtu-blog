package com.yigalaxy.yiguixingtu.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 后台「访问统计」下钻出来的**一行访客**：一个 IP + 它这段时间的表现。
 *
 * 【为什么按 IP 聚合，而不是把每条访问记录列出来】
 *   要回答的问题是"都是谁来了、谁来得最多"。
 *   原样列明细的话，同一个刷子刷一百次就占一百行，真正的问题
 *   （"是不是同一个人在刷"）反而被埋掉了 —— 而聚合之后一眼就能看到。
 *   这也是整张表按 IP 去重算 UV 的同一套口径（见 VisitLogMapper.selectDaily）。
 *
 * 【为什么带 lastVisit】次数相同或相近时，"最后一次是什么时候"是判断
 *   "这人还在不在"的依据 —— 一个上周来过 20 次、之后再没出现的 IP，
 *   和一个今天来了 20 次的 IP，含义完全不同。
 *
 * 【⚠️ 这个 VO 只在后台接口出现，和 AdminCommentVO 是同一个理由】
 *   它带着访客的完整 IP。给公开接口加一个这样的类型，等于把
 *   "任何人都能看到访问过本站的 IP" 变成事实 —— 所以它必须只出现在
 *   {@code /admin/**} 下面，那条路径在 SecurityConfig 里要求 ADMIN 角色。
 */
@Schema(description = "访客 IP（按 IP 聚合后的一个访客）")
public record VisitorIpVO(

        @Schema(description = "访客 IP")
        String ip,

        /**
         * 归属地。**可能为 null**：历史记录没有这一列，IPv6 与畸形值也解析不出。
         * 前端显示成「—」——不要拼成空串，那和"解析出来是空的"分不开。
         */
        @Schema(description = "IP 归属地（解析不出为 null）")
        String region,

        @Schema(description = "这段时间的访问次数")
        long visits,

        @Schema(description = "最后一次访问的时间")
        LocalDateTime lastVisit) {
}
