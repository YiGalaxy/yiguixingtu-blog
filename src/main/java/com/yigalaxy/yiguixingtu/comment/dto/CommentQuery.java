package com.yigalaxy.yiguixingtu.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 评论分页条件；status 仅供后台查询使用。 */
@Data
@Schema(description = "评论分页查询条件")
public class CommentQuery {

    public static final long MAX_PAGE_SIZE = 50L;

    public static final long DEFAULT_PAGE_SIZE = 10L;

    @Schema(description = "页码，从1开始", example = "1")
    private Long page = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long size = 10L;

    @Schema(description = "文章ID筛选")
    private Long articleId;

    @Schema(description = "状态筛选：0待审核 1已通过 2已拒绝（仅后台生效）")
    private Integer status;
}
