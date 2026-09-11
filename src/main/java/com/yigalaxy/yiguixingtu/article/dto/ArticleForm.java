package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
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

    /**
     * 附件列表（**整体替换**语义：提交什么，最终就是什么 —— 与上面的 tagIds 完全一致）。
     *
     * 【为什么是"整体替换"而不是"增量"】
     *   与标签那一大段理由相同，而且附件这里更明显：编辑界面里的附件是一个
     *   "可以加、可以删"的列表，用户看到的列表就是最终状态。
     *   如果改成增量（前端只报"加了哪些、删了哪些"），就需要前后端对
     *   "初始状态"有一致的认知 —— 一旦有人拿着过期的页面提交，
     *   结果会悄悄错掉（附件没了、或者多出一个），而且极难发现。
     *
     * 【⚠️ 整体替换的一个后果，必须写清楚】
     *   {@code attachments} 不传（null）与传空数组【效果相同】：都表示
     *   "这篇文章没有附件"。也就是说，将来若有某个调用方（脚本、导入工具）
     *   只想改标题、随手发了一个不含 attachments 的请求，
     *   它会把原有附件全部清掉。
     *   这是刻意的（与 tagIds 的行为一字不差：老版本的调用方就是不带这个字段的，
     *   如果把它解释成"别动附件"，那么"用户清空全部附件"就永远生效不了 ——
     *   两种解释只能选一个，而"提交什么就是什么"是更容易预测的那一个）。
     *   前端保存文章时应当始终回传完整的附件列表（编辑页面已经把现有附件显示出来了）。
     *
     * 【数量上限 20 为什么写在这里而不是 Service】
     *   它是一条与配置无关的固定规则（不是 app.upload.* 里的可调项），
     *   注解能表达就写在注解上 —— 校验失败由 GlobalExceptionHandler 转成 400，
     *   而且 Swagger 文档里也会带上这条限制，前端一眼能看到。
     *   20 的依据：附件是"一篇文章的补充材料"（论文 + 数据 + 代码 + 演示视频…），
     *   20 条对正常使用绰绰有余，同时防止有人把文章当成网盘目录来用
     *   （每条最大 100MB，20 条就是一个文章上限 2GB —— 超过这个量级
     *     就不该走博客附件，而该去网盘/对象存储）。
     *
     * 【@Valid 的位置：写在【类型参数】上，不要写在字段上】
     *   它让列表里的【每一项】都走一遍 ArticleAttachmentForm 上的校验注解
     *   （名字长度、地址非空……）。少了它，@Size/@NotBlank 只作用在"列表本身"上，
     *   里面的元素是什么形状就没人管了 —— 这是嵌套校验最常漏的一步。
     *   ⚠️ 写成字段级 {@code @Valid List<X>} 也能生效，但 Hibernate Validator
     *   会报 HV000271 弃用告警（推荐把注解放到类型参数上，即
     *   {@code List<@Valid X>}）。这里按推荐写法写，启动日志里就不会多一条告警 ——
     *   "一开始就有的告警"最容易被当成噪音，然后在真正的告警出现时被一起忽略。
     */
    @Size(max = 20, message = "附件最多 20 个")
    @Schema(description = "附件列表；不传或传空数组表示清空附件（整体替换语义）")
    private java.util.List<@Valid ArticleAttachmentForm> attachments;
}