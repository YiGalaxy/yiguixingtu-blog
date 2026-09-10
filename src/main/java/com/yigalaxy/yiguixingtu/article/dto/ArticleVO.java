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

    /**
     * 该文章挂的标签（只有 id / name，按 sort、id 排序）。
     *
     * 【为什么用 TagVO 而不是 List&lt;String&gt;】
     *   前端点标签要跳转到"按标签筛选"的列表页，那需要标签 id；
     *   只给名字的话前端还得再查一次 id —— 而名字是可能改的，
     *   用名字做筛选条件会让链接在标签改名后失效。
     *
     * 【它是怎么被填上的（这点很重要）】
     *   列表接口一页 10 篇，如果每篇都单独查一次标签就是 10 次 SQL（N+1）。
     *   所以 Service 用一条 IN 查询把整页的标签一次取回来，在内存里分组填充
     *   （见 TagService.mapByArticleIds）。
     *
     * 【空数组还是 null】
     *   没有标签时给【空数组】而不是 null：前端可以直接 v-for，
     *   不用再写一层判空（这一点和 TagVO.articleCount 给 0 而不是 null 是同一个考虑）。
     */
    @Schema(description = "标签列表（没有标签时是空数组）")
    private java.util.List<com.yigalaxy.yiguixingtu.tag.dto.TagVO> tags = new java.util.ArrayList<>();

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