package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.util.StringUtils;

/** 文章分页查询条件，前台和后台接口共用。 */
@Data
@Schema(description = "文章分页查询条件")
public class ArticleQuery {

    /** Service 分页上限；必须与缓存 key 的归一化规则保持一致。 */
    public static final long MAX_PAGE_SIZE = 50L;

    /** 没传 size 时用多少条 */
    public static final long DEFAULT_PAGE_SIZE = 10L;

    @Schema(description = "页码，从1开始", example = "1")
    private Long page = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long size = 10L;

    @Schema(description = "关键词：模糊匹配 标题 或 摘要")
    private String keyword;

    @Schema(description = "分类ID筛选")
    private Long categoryId;

    /** 标签 ID 筛选。 */
    @Schema(description = "标签ID筛选")
    private Long tagId;

    /** 仅后台查询生效；前台服务层始终固定为已发布。 */
    @Schema(description = "状态筛选：0草稿 1已发布（仅后台生效）")
    private Integer status;

    @Schema(description = "排序字段：id / title / status / viewCount / isTop / createTime / updateTime",
            example = "createTime")
    private String sortField;

    @Schema(description = "排序方向：asc 升序 / desc 降序", example = "desc")
    private String sortOrder;

    /**
     * 生成前台已发布列表缓存 key。归一化规则必须与 Service 分页规则一致，
     * 以避免默认值、空条件和超大 size 产生重复或错误缓存。
     */
    public String toCacheKey() {
        long effectivePage = (page == null || page < 1) ? 1L : page;

        long effectiveSize = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : size;
        if (effectiveSize > MAX_PAGE_SIZE) {
            effectiveSize = MAX_PAGE_SIZE;
        }

        String kw = StringUtils.hasText(keyword) ? keyword.trim() : "all";
        String sort = StringUtils.hasText(sortField) ? sortField : "default";
        String order = StringUtils.hasText(sortOrder) ? sortOrder : "default";

        return effectivePage
                + ":" + effectiveSize
                + ":" + (categoryId == null ? "all" : categoryId)
                + ":" + (tagId == null ? "all" : tagId)
                + ":" + kw
                + ":" + sort
                + ":" + order;
    }
}
