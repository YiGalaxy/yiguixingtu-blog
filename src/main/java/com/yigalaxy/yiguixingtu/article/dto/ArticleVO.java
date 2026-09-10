package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章信息（对外返回）。
 */
@Data
@Schema(description = "文章信息")
public class ArticleVO {

    @Schema(description = "文章ID")
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "摘要")
    private String summary;

    /**
     * 【列表接口不返回它】
     * 正文是 longtext，可能几十 KB。列表页一次 10 篇，
     * 全查出来纯属浪费带宽和内存 —— 列表也用不到正文。
     * 所以：列表接口这里恒为 null，只有详情接口才填上。
     */
    @Schema(description = "正文（仅详情接口返回，列表为 null）")
    private String content;

    @Schema(description = "封面图URL")
    private String cover;

    @Schema(description = "分类ID")
    private Long categoryId;

    /** 由 Service 批量查出来后填充，方便前端直接显示分类名，不用再查一次 */
    @Schema(description = "分类名称")
    private String categoryName;

    @Schema(description = "状态：0草稿 1已发布")
    private Integer status;

    @Schema(description = "浏览量")
    private Integer viewCount;

    @Schema(description = "是否置顶：0否 1是")
    private Integer isTop;

    @Schema(description = "作者用户ID")
    private Long authorId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}