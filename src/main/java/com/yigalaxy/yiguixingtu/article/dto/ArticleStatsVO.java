package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 站点统计（首页那几个数字）。
 *
 * 【为什么单独搞一个 VO，而不是让前端各查各的】
 *   首页要显示"文章数 / 浏览数 / 分类数"三个数字。
 *   如果让前端分别去调列表接口、详情接口、分类接口再自己求和，会有三个问题：
 *     ① 前端要拉全量列表才能求和 —— 文章一多就是几百 KB 的请求，纯属浪费
 *     ② 数字的口径会散落在前端（比如"草稿算不算"），改口径要改前端
 *     ③ 三个请求还可能拿到不同时刻的数据，三个数字凑在一起看着不自洽
 *   所以后端一次性算好、一次返回，口径写在 SQL 里、由后端负责。
 *
 * 【口径：只统计"已发布"】
 *   草稿是作者自己还没写完的东西，不该出现在对外展示的数字里 ——
 *   否则首页写着"共 50 篇"，游客点进去只数得出 30 篇，显得像在虚报。
 *
 * 【这个类同时被两处用到，说明一下免得看着别扭】
 *   · ArticleMapper.selectPublishedAggregate() 用它接住"文章数 + 总浏览量"两个聚合值
 *     （此时 categoryCount 是 null，不是我漏了）
 *   · ArticleService.stats() 再补上分类数，组成完整结果返回给前端
 *   之所以共用同一个类而不是再造一个"聚合结果类"：字段完全一样，
 *   多造一个只会多一处要同步维护的定义。
 */
@Data
@Schema(description = "站点统计")
public class ArticleStatsVO {

    @Schema(description = "已发布文章数", example = "128")
    private Long articleCount;

    @Schema(description = "已发布文章的总浏览量", example = "25600")
    private Long viewCount;

    @Schema(description = "分类数", example = "8")
    private Long categoryCount;

    public ArticleStatsVO() {
    }

    public ArticleStatsVO(Long articleCount, Long viewCount, Long categoryCount) {
        this.articleCount = articleCount;
        this.viewCount = viewCount;
        this.categoryCount = categoryCount;
    }
}
