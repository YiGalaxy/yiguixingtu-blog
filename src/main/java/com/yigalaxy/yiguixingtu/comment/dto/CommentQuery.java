package com.yigalaxy.yiguixingtu.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 评论分页查询条件。
 *
 * 【前后台共用一个查询对象，但"状态"这个条件只有后台认】
 *   前台接口会把 status 写死成 1（已通过）——
 *   和文章前台查询写死 status = 1 是同一个套路：
 *   不管前端传什么，都拿不到未审核的内容。
 *   所以这个字段的注释里明确写了"仅后台生效"，免得以后有人以为前台也能按状态筛。
 */
@Data
@Schema(description = "评论分页查询条件")
public class CommentQuery {

    /** 一页最多多少条。评论比文章轻，但也没必要让人一次拉几百条 */
    public static final long MAX_PAGE_SIZE = 50L;

    public static final long DEFAULT_PAGE_SIZE = 10L;

    @Schema(description = "页码，从1开始", example = "1")
    private Long page = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long size = 10L;

    @Schema(description = "文章ID筛选")
    private Long articleId;

    /**
     * 状态筛选：0待审核 1已通过 2已拒绝。
     * ⚠️ 只有【后台】接口认它；前台接口会在 Service 里写死 status = 1。
     */
    @Schema(description = "状态筛选：0待审核 1已通过 2已拒绝（仅后台生效）")
    private Integer status;
}
