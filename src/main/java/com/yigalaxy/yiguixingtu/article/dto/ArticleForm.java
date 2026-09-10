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
}