package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 站点公开统计；文章数和浏览量只统计已发布内容。 */
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
