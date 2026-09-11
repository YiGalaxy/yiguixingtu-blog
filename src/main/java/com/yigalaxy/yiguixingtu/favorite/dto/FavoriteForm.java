package com.yigalaxy.yiguixingtu.favorite.dto;

import com.yigalaxy.yiguixingtu.common.validation.UrlPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑收藏的提交表单。
 *
 * 【校验的强度按"字段对谁可见"分档，不是一刀切】
 *   · title / url —— 必填，且 url 必须是 http(s)（白名单，理由见 UrlPatterns）：
 *     它们会被前台渲染成标题和 {@code <a href>}
 *   · category —— 只做长度上限，内容不校验：它是站长自己写的分组名，
 *     任何"分组名格式规则"都只会挡住正当用法（"面试题/八股"这种带斜杠的写法很常见）
 *   · description —— 只做长度上限
 *   结论：**只有会被浏览器解释的字段才做格式白名单**（url），其余字段做长度与必填。
 */
@Data
@Schema(description = "收藏提交表单")
public class FavoriteForm {

    /**
     * 标题，1–100 字（与列长度一致）。
     * ⚠️ 它是站长自己填的展示名，后端【不会】去目标网页抓 <title>（理由见实体注释）。
     */
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长 100 字")
    @Schema(description = "标题", example = "MySQL 索引原理图解")
    private String title;

    /**
     * 目标地址，必填。
     * 空串由 {@code @NotBlank} 拦住（收藏没有地址就没有意义）；
     * 非空的值必须是 http(s) 开头的完整地址（白名单，只有这两条协议）。
     */
    @NotBlank(message = "地址不能为空")
    @Size(max = 255, message = "地址最长 255 字")
    @Pattern(regexp = UrlPatterns.EXTERNAL_URL, message = UrlPatterns.EXTERNAL_URL_MESSAGE)
    @Schema(description = "目标地址", example = "https://example.com/post/1")
    private String url;

    /** 备注/说明，可选，最长 500（与列长度一致） */
    @Size(max = 500, message = "备注最长 500 字")
    @Schema(description = "备注/说明（可选）")
    private String description;

    /**
     * 分组名，可选，最长 50（与列长度一致）。
     * 【为什么不做成"从已有分组里选"】那要求后端先提供一个分组列表接口，
     * 而分组本来就该在录入时随手新增（"工具"、"文章"、"下次再看"）。
     * 自由文本 + 前端按已有的值给下拉建议，比强制维护一份分组字典轻得多。
     */
    @Size(max = 50, message = "分组名最长 50 字")
    @Schema(description = "分组名（自由文本，可选 = 未分组）", example = "工具")
    private String category;

    /** 排序值，越小越靠前；不传时新建按 0、编辑保持原值（与 tag / category / 友链 / 项目一致） */
    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    /** 状态：0 隐藏 / 1 显示；不传时新建按 1、编辑保持原值 */
    @Min(value = 0, message = "状态只能是 0(隐藏) 或 1(显示)")
    @Max(value = 1, message = "状态只能是 0(隐藏) 或 1(显示)")
    @Schema(description = "状态：0隐藏 1显示（不传时新建按 1、编辑保持原值）", example = "1")
    private Integer status;
}
