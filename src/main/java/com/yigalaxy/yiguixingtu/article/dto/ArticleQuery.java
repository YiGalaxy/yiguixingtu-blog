package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文章分页查询条件（前台后台共用）。
 */
@Data
@Schema(description = "文章分页查询条件")
public class ArticleQuery {

    @Schema(description = "页码，从1开始", example = "1")
    private Long page = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long size = 10L;

    @Schema(description = "关键词：模糊匹配 标题 或 摘要")
    private String keyword;

    @Schema(description = "分类ID筛选")
    private Long categoryId;

    /**
     * 【注意】这个字段只有后台接口认。
     * 前台调 /article/page 时，Service 会写死 status=1，
     * 前端就算传 status=0 想偷看草稿，也会被无视。
     */
    @Schema(description = "状态筛选：0草稿 1已发布（仅后台生效）")
    private Integer status;

    @Schema(description = "排序字段：id / title / status / viewCount / isTop / createTime / updateTime",
            example = "createTime")
    private String sortField;

    @Schema(description = "排序方向：asc 升序 / desc 降序", example = "desc")
    private String sortOrder;
}
