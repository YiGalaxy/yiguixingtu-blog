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

    /**
     * 这篇文章的附件（name / url / size），没有附件时是空数组。
     *
     * 【只有【详情】接口才填它，列表接口恒为空数组】
     *   与正文（content）同一个考虑：列表页一次 10 篇文章，
     *   每篇再查一次附件就是 10 次额外 SQL（N+1），而列表卡片上
     *   根本不会显示附件（它要的是标题、摘要、封面）。
     *   ⚠️ 与 tags 的处理方式不同（标签在列表里也填了），
     *   差别在于"列表用不用得到"：标签是列表卡片上要显示的小标签，
     *   附件只出现在文章页 —— 为了一个用不到的字段多做一次查询，
     *   是把成本花在了不产生价值的地方。
     *
     * 【它是在哪一层填上的】
     *   两处详情转换都会填：ArticleServiceImpl.toVO（后台详情，含草稿）
     *   与 PublishedArticleCache.toVO（前台详情，带 Redis 缓存）。
     *   两处都调的同一个方法：ArticleAttachmentVO.fromEntities
     *   —— 为什么必须只留一份转换，见那个方法的注释。
     *
     * 【空数组还是 null】
     *   给【空数组】而不是 null：前端拿到详情就可以直接
     *   {@code v-for="a in article.attachments"}，不必再写一层判空
     *   （与 tags 是同一条约定）。
     */
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