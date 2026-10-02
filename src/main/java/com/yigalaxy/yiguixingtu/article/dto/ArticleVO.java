package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 对外返回的文章信息。 */
@Data
@Schema(description = "文章信息")
public class ArticleVO {

    @Schema(description = "文章ID")
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "摘要")
    private String summary;

    /** 正文仅由详情接口填充，列表接口保持 null。 */
    @Schema(description = "正文（仅详情接口返回，列表为 null）")
    private String content;

    @Schema(description = "封面图URL")
    private String cover;

    @Schema(description = "分类ID")
    private Long categoryId;

    /** 服务层批量填充的分类名称。 */
    @Schema(description = "分类名称")
    private String categoryName;

    /** 标签列表；无标签时返回空数组。 */
    @Schema(description = "标签列表（没有标签时是空数组）")
    private java.util.List<com.yigalaxy.yiguixingtu.tag.dto.TagVO> tags = new java.util.ArrayList<>();

    /** 附件列表；详情接口填充，列表接口保持空数组。 */
    @Schema(description = "附件列表（没有附件时是空数组；列表接口恒为空数组）")
    private java.util.List<ArticleAttachmentVO> attachments = new java.util.ArrayList<>();

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
