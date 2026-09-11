package com.yigalaxy.yiguixingtu.project.dto;

import com.yigalaxy.yiguixingtu.common.validation.UrlPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑项目的提交表单。
 *
 * 【校验同友链：三层，各管一件事】
 *   ① {@code @NotBlank} / {@code @Size} —— 必填与长度上限，长度与数据库列一一对应
 *   ② {@code @Pattern} —— URL 格式白名单（只放行 http(s)，理由见 UrlPatterns：
 *      这些地址会被渲染成 {@code <a href>}，黑名单拦不住 javascript: 这类协议）
 *   ③ Service 里的业务校验 —— "status 只能是 0/1"，以及本项目特有的一条：
 *      **url 与 repo 不能同时为空**（它跨两个字段，单字段注解表达不了，
 *        所以只能用 @AssertTrue 之类的类级校验或者干脆写在 Service 里 ——
 *        这里选 Service，因为 Service 是"所有调用路径的必经之地"）
 */
@Data
@Schema(description = "项目提交表单")
public class ProjectForm {

    /**
     * 项目名称，1–100 字。
     * 为什么比友链的 50 长：项目名常常带着版本或副标题
     * （"亿轨星途博客系统 · 前后端全栈"），50 字会把这类名字挤掉半截。
     */
    @NotBlank(message = "项目名称不能为空")
    @Size(max = 100, message = "项目名称最长 100 字")
    @Schema(description = "项目名称", example = "亿轨星途博客系统")
    private String name;

    /** 项目简介，可选，最长 500（与列长度一致）：说清"做了什么、用了什么"，再长就该写文章了 */
    @Size(max = 500, message = "项目简介最长 500 字")
    @Schema(description = "项目简介")
    private String description;

    /**
     * 在线演示地址，可选。
     * ⚠️ 它与 repo 【不能同时为空】—— 两个都空的项目卡片点不出任何东西，
     * 访客只会以为它坏了。这条规则在 Service 里（跨字段，注解表达不了）。
     */
    @Size(max = 255, message = "在线地址最长 255 字")
    @Pattern(regexp = UrlPatterns.EXTERNAL_URL, message = UrlPatterns.EXTERNAL_URL_MESSAGE)
    @Schema(description = "在线演示地址（与仓库地址至少填一个）")
    private String url;

    /** 代码仓库地址，可选；同样与 url 至少填一个 */
    @Size(max = 255, message = "仓库地址最长 255 字")
    @Pattern(regexp = UrlPatterns.EXTERNAL_URL, message = UrlPatterns.EXTERNAL_URL_MESSAGE)
    @Schema(description = "代码仓库地址（与在线地址至少填一个）")
    private String repo;

    /** 封面图，可选；允许上传接口返回的绝对地址，也允许 / 开头的站内路径 */
    @Size(max = 255, message = "封面图地址最长 255 字")
    @Pattern(regexp = UrlPatterns.IMAGE_URL, message = UrlPatterns.IMAGE_URL_MESSAGE)
    @Schema(description = "封面图地址（可选）")
    private String cover;

    /**
     * 技术栈，逗号分隔，最长 200。
     * 【为什么不在 DTO 里把它拆成 List】
     *   前端提交和展示用的是同一个字段，保持"提交什么字符串、列表就返回什么字符串"
     *   最不容易出错（拆成数组之后，后台表单要把数组拼回字符串、还要定"空项怎么处理"）。
     *   前端 split(',') 一次即可，代价比接口形状不对称小得多。
     */
    @Size(max = 200, message = "技术栈最长 200 字")
    @Schema(description = "技术栈（逗号分隔）", example = "Spring Boot,MySQL,Redis")
    private String tech;

    /** 排序值，越小越靠前；不传时新建按 0、编辑保持原值（与 tag / category / 友链一致） */
    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    /** 状态：0 隐藏 / 1 显示；不传时新建按 1、编辑保持原值 */
    @Min(value = 0, message = "状态只能是 0(隐藏) 或 1(显示)")
    @Max(value = 1, message = "状态只能是 0(隐藏) 或 1(显示)")
    @Schema(description = "状态：0隐藏 1显示（不传时新建按 1、编辑保持原值）", example = "1")
    private Integer status;
}
