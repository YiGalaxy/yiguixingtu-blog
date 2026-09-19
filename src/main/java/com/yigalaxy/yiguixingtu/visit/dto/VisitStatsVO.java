package com.yigalaxy.yiguixingtu.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 后台「访问统计」的整页数据。
 *
 * 【为什么所有分组都用同一个 Slice 形状】身份 / 来源 / 设备三处的数据形状完全一样
 *   ——  都是"某个取值有多少条"。给它们各定义一个类型（VisitorTypeCount、
 *   RefererCount……）只是把同一个东西写三遍，前端也要写三遍渲染逻辑。
 *   用统一的 {@code (key, value)} 之后，前端一个循环就能把它们都画成饼图。
 *
 * 【key 是后端给的英文常量，中文在前端翻】理由与 UploadReference.type 一致：
 *   这些值是**统计口径**，将来可能加档（"平板"、把"搜索引擎"从外链里单独拆出来），
 *   而口径属于后端（它才知道怎么分类）。前端只负责把已知的 key 翻成中文，
 *   遇到不认识的 key 就原样显示 —— 后端加档时前端不会崩。
 */
@Schema(description = "访问统计报表")
public record VisitStatsVO(

        @Schema(description = "统计范围：最近多少天（含今天）")
        int days,

        @Schema(description = "区间内的总访问次数（PV）")
        long totalPv,

        @Schema(description = "区间内的独立访客数（UV，按 IP 去重）")
        long totalUv,

        @Schema(description = "其中第一次来本站的访客数（按 IP 判断）")
        long newVisitors,

        @Schema(description = "按天曲线（只含【有记录】的那些天，前端自己补零）")
        List<DailyPoint> daily,

        @Schema(description = "访客身份构成：GUEST / USER / ADMIN")
        List<Slice> visitorTypes,

        @Schema(description = "来源构成：INTERNAL / EXTERNAL / DIRECT")
        List<Slice> refererTypes,

        @Schema(description = "设备构成：PC / MOBILE")
        List<Slice> devices,

        @Schema(description = "访问最多的页面（前 10）")
        List<Slice> topPaths,

        @Schema(description = "访问最多的文章（前 10，带标题）")
        List<ArticlePoint> topArticles) {

    /**
     * 曲线上的一天。
     *
     * @param date 形如 {@code 2026-09-19}
     * @param pv   当天的访问次数
     * @param uv   当天的独立访客数（按 IP 去重）
     */
    @Schema(description = "每日数据点")
    public record DailyPoint(String date, long pv, long uv) {
    }

    /**
     * 一个分组项。
     *
     * @param key   分组值（英文常量，见 VisitLog 上的 VISITOR_* / REFERER_* / DEVICE_*）
     * @param value 计数
     */
    @Schema(description = "分组计数")
    public record Slice(String key, long value) {
    }

    /**
     * 一篇文章的访问量。
     *
     * @param id    文章 id（**可能对应一篇已被删除的文章**：历史访问记录仍在，
     *              而文章被逻辑删掉了 —— 那时 title 为 null，前端显示"（已删除）"）
     * @param title 文章标题，可能为 null
     * @param pv    访问次数
     */
    @Schema(description = "文章的访问量")
    public record ArticlePoint(Long id, String title, long pv) {
    }
}
