package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存文章时提交的【单个附件】（{@code attachments} 数组里的一项）。
 *
 * 【字段形状就是上传接口返回的那三个】{@code {name, url, size}} ——
 *   前端的用法是"上传拿到什么、就原样放进这个数组"，中间不需要任何转换。
 *   这种"出入同形"是刻意的：任何一处改名或改单位，
 *   都会变成前端要写一段映射代码，而那段代码一旦漏改，
 *   表现是"附件列表里的名字变成 uuid"或者"大小显示成 12.58 字节"。
 *
 * 【校验分工：这里管"形状"，Service 管"业务规则"】
 *   · 这里（Bean Validation 注解）：非空、长度上限、大小非负 —— 都是
 *     与配置无关的常量规则，由 @Valid 自动触发，失败由 GlobalExceptionHandler
 *     统一转成 400
 *   · ArticleServiceImpl.validateAttachments：数量上限、size 上限、
 *     url 必须落在本项目上传前缀之内 —— 这三条要么是跨字段的、
 *     要么必须读配置（app.upload.attachment-max-size / base-url），
 *     注解是编译期常量，做不到"跟着配置走"
 *
 *   ⚠️ 尤其【不】在这里写 @Max 去表达"size ≤ 100MB"：
 *      那会把上限写死在编译期，与 app.upload.attachment-max-size 变成两处真相。
 *      站长哪天把附件上限调到 200MB，注解这一层还在按 100MB 拦 ——
 *      而且报错信息是一句与配置无关的"大小超出限制"，排查时要翻三层。
 *      所以 size 的上限只由 Service 按配置判断，一条规则一个出处。
 */
@Data
@Schema(description = "文章附件（保存文章时提交）")
public class ArticleAttachmentForm {

    /**
     * 附件显示名。
     *
     * 【100 这个数字是【三处共用】的】库列 article_attachment.name varchar(100)、
     * 这里 @Size(max = 100)、以及上传接口返回 name 时的截断长度
     * （UploadService.sanitizeName）—— 三处必须一致，
     * 否则会出现"上传成功、保存文章时却报名字过长"这种用户完全无法理解、
     * 也完全无法自救的失败（他什么都没填，是文件名太长）。
     */
    @NotBlank(message = "附件名不能为空")
    @Size(max = 100, message = "附件名最长 100 字")
    @Schema(description = "附件显示名", example = "毕业论文.pdf")
    private String name;

    /**
     * 附件地址。
     *
     * 【255 与库列一致】article_attachment.url varchar(255)。
     *
     * 【格式白名单为什么在 Service 里，而不是这里的 @Pattern】
     *   因为它依赖【配置】（app.upload.base-url），而注解写不了动态值：
     *   线上 base-url 是 https://域名/api、本地是 http://localhost:8082，
     *   写死任何一条都会让另一套环境全挂。所以这里只做长度限制，
     *   真正的"必须落在本站上传前缀之内"由 ArticleServiceImpl 判断。
     *   ⚠️ 这条校验不是可选项：不校验的话，任何人都能把
     *      https://别人的钓鱼站/xxx.exe 填进 attachments，
     *      它会以"本站文章附件"的名义展示给读者 —— 等于替对方做信任背书。
     */
    @NotBlank(message = "附件地址不能为空")
    @Size(max = 255, message = "附件地址过长")
    @Schema(description = "附件地址（必须是本项目上传目录内的地址）")
    private String url;

    /**
     * 文件大小（字节）。
     *
     * 【为什么这只是 @Min(0)（非负），不是 @Max(100MB)】见类注释里的说明：
     *   上限由配置决定，判断在 Service。这里挡住的是"负数"这种明显无意义的值 ——
     *   负数一旦存进库，前端渲染的大小会变成 "-1 字节" 这种没人能解释的东西。
     *
     * 【为什么不校验"它必须等于磁盘上那个文件的真实大小"】
     *   那需要在上传目录里 stat 一次文件（多一次 IO），收益却很小：
     *   这个值只用于展示。真被改错，最坏的结果是列表里的大小数字不准 ——
     *   不影响下载（下载走的是 URL，浏览器看到的是真实字节流）。
     *   为了它做一次磁盘检查，是把"展示字段"当成"安全字段"来防，不划算。
     */
    @NotNull(message = "附件大小不能为空")
    @Min(value = 0, message = "附件大小不能为负数")
    @Schema(description = "文件大小（字节）", example = "1258291")
    private Long size;
}
