package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑文章的提交表单。
 *
 * 【为什么新建和编辑共用一个 DTO？】
 * 两者需要填的字段完全一样，拆成两个类只会增加重复。
 * 校验注解写在这里，Controller 上加 @Valid 就会自动生效，
 * 校验失败由 GlobalExceptionHandler 统一转成 400。
 */
@Data
@Schema(description = "文章提交表单")
public class ArticleForm {

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最长 200 字")
    @Schema(description = "标题", example = "Spring Security 入门笔记")
    private String title;

    @Size(max = 500, message = "摘要最长 500 字")
    @Schema(description = "摘要。留空则自动从正文截取")
    private String summary;

    @Schema(description = "正文（Markdown 源码）")
    private String content;

    @Size(max = 255, message = "封面地址过长")
    @Schema(description = "封面图URL")
    private String cover;

    @Schema(description = "分类ID。可以不选")
    private Long categoryId;

    /** 不给默认值的话，用户传 null 时会写进库里的也是 null，而该列 NOT NULL 会报错 */
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    @Schema(description = "状态：0草稿 1已发布。默认 0（草稿）", example = "0")
    private Integer status = 0;

    @Min(value = 0, message = "置顶只能是 0 或 1")
    @Max(value = 1, message = "置顶只能是 0 或 1")
    @Schema(description = "是否置顶：0否 1是。默认 0", example = "0")
    private Integer isTop = 0;

    /**
     * 标签 id 列表（**覆盖式**语义：提交什么，最终就是什么）。
     *
     * 【为什么是"覆盖式"而不是"增量"】
     *   编辑文章的界面里，标签是一个多选框：用户看到的勾选状态就是最终状态。
     *   前端把整份勾选结果发上来，后端"先清空再写入"是最不容易出错的语义 ——
     *   增量（只传新增/移除的那些）需要前后端对"初始状态"有一致的认知，
     *   一旦有人拿着过期页面提交，结果就会悄悄错掉，而且很难发现。
     *
     * 【为什么不加非空校验】
     *   不传 / 传空数组都表示"这篇文章没有标签"，是完全合法的状态
     *   （用户可能就是想清空标签）。真正需要拦住的是"传了不存在的标签 id"，
     *   那个校验必须查库，注解做不到 —— 它在 TagService.replaceArticleTags 里。
     */
    @Schema(description = "标签ID列表；不传或传空数组表示清空标签")
    private java.util.List<Long> tagIds;
}