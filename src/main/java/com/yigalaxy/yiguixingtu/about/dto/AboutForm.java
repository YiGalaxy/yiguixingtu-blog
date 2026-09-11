package com.yigalaxy.yiguixingtu.about.dto;

import com.yigalaxy.yiguixingtu.common.validation.UrlPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 关于页的提交表单（只有"保存"这一种用法，没有新建 —— 那一行是迁移脚本插的）。
 *
 * 【校验的强度按"字段会不会被浏览器解释"分档】
 *   · 会进 {@code <a href>} / {@code <img src>} 的：avatar、github —— 格式白名单（只放行 http(s)）
 *   · 会进 {@code mailto:} 的：email —— 校验邮箱格式（写错的邮箱点了打不开，
 *     而且容易被当成"站点有问题"）
 *   · 只是纯文字的：nickname、wechat、qq —— 【不做格式校验】，只卡长度。
 *     微信号有字母数字下划线、QQ 栏里有人填 QQ 邮箱或昵称，加规则只会挡住正当输入
 *   · bio 是长文本：只卡 5000 字上限（再多就该单独写一篇文章，而不是塞在关于页里）
 *
 * 【为什么没有 id 字段】这一行永远是 id = 1，前端传它没有意义，
 * 后端也不接受"改哪一行"这种参数 —— 单条记录的接口不该留一个能改错行的口子。
 */
@Data
@Schema(description = "关于页提交表单")
public class AboutForm {

    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称最长 50 字")
    @Schema(description = "昵称", example = "别太在亿啦")
    private String nickname;

    /** 头像：可为空；允许上传接口返回的绝对地址，也允许 / 开头的站内路径 */
    @Size(max = 255, message = "头像地址最长 255 字")
    @Pattern(regexp = UrlPatterns.IMAGE_URL, message = UrlPatterns.IMAGE_URL_MESSAGE)
    @Schema(description = "头像地址（可选）")
    private String avatar;

    /** 自我介绍：允许写 Markdown（后端存原文、原样返回，渲染交给前端，与文章正文一致） */
    @Size(max = 5000, message = "自我介绍最长 5000 字")
    @Schema(description = "自我介绍（长文本，可写 Markdown）")
    private String bio;

    /**
     * 邮箱：给访客发信用的。
     * 【为什么空串也算合法】{@code @Email} 对 null 与空串都放行 ——
     * 这正是我们要的：不想公开邮箱时把这一栏清空即可（Service 会归一化成 null）。
     */
    @Size(max = 100, message = "邮箱最长 100 字")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "邮箱（可选）", example = "me@example.com")
    private String email;

    /** GitHub：会被渲染成链接，所以走 http(s) 白名单 */
    @Size(max = 255, message = "GitHub 地址最长 255 字")
    @Pattern(regexp = UrlPatterns.EXTERNAL_URL, message = UrlPatterns.EXTERNAL_URL_MESSAGE)
    @Schema(description = "GitHub 地址（可选）", example = "https://github.com/YiGalaxy")
    private String github;

    /** 微信：纯文字，不做格式校验（理由见类注释） */
    @Size(max = 50, message = "微信最长 50 字")
    @Schema(description = "微信（可选）")
    private String wechat;

    /** QQ：纯文字，不做格式校验 */
    @Size(max = 30, message = "QQ 最长 30 字")
    @Schema(description = "QQ（可选）")
    private String qq;
}
