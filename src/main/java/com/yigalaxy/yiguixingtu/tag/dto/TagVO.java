package com.yigalaxy.yiguixingtu.tag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 标签视图对象（返回给前端的形状）。
 *
 * 【为什么带上 articleCount】
 *   标签页/标签云要显示"每个标签下有几篇文章"，而且希望按热度排序。
 *   如果列表接口不给这个数，前端就得对每个标签各发一次请求去数
 *   （N 个标签 = N 次请求，典型的 N+1 问题）。
 *   在后端用一条 GROUP BY 一次算出来，是这里唯一合理的做法。
 *
 * 【这个数统计的是什么口径】
 *   【已发布】的文章数（草稿不计）。理由：标签是给访客看的导航，
 *   统计里混进"只有管理员看得到的草稿"，会让访客点进去发现"只有 2 篇却有 5 篇的计数"。
 *   口径写在 Service 里，并且有用例钉着（草稿不计入）。
 */
@Data
@Schema(description = "标签（含文章数）")
public class TagVO {

    @Schema(description = "标签ID", example = "1")
    private Long id;

    @Schema(description = "标签名", example = "Spring Boot")
    private String name;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "该标签下【已发布】的文章数", example = "3")
    private Long articleCount;
}
