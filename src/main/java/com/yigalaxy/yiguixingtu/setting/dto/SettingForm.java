package com.yigalaxy.yiguixingtu.setting.dto;

import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 站点设置的提交表单（只有"保存"这一种用法，没有新建 ——
 * 那一行由 V12 迁移脚本插好，见迁移脚本里为什么）。
 *
 * 【校验强度按"这个字段会变成什么"分档，与 AboutForm 是同一条规则】
 *   · 会进 {@code <title>} / 页眉页脚文本的：siteName —— 必填 + 卡长度。
 *     为什么必填：它是站点身份，空字符串会让标题变成" · 那句自我介绍"这种残句
 *   · 会进页脚文字的：icpNumber / copyright —— 【只卡长度，不做格式校验】。
 *     备案号的编号形态有好几种，加正则只会挡住正当输入；它们也不会被渲染成链接
 *   · 只是公告文字的：announcement —— 只卡 500 字（理由见迁移脚本）
 *   · 是开关的：commentEnabled —— {@code @NotNull}，但【不写默认值】：
 *     少传一个字段就被当成"关闭评论"太危险了，宁可让请求失败
 *
 * 【⚠️ pageSize 的上限为什么引用 ArticleQuery.MAX_PAGE_SIZE，而不是写一个 50】
 *   因为那个 50 不是"一个好看的数字"，而是文章列表接口【真的会把 size 夹到】的值
 *   （见 ArticleQuery 与 ArticleServiceImpl.doPage）。
 *   如果这里允许填 100，站长会得到一个"设置成功但不生效"的开关：
 *   首页仍然只列出 50 篇，而且不会报任何错 —— 这正是最难查的一类问题。
 *   引用同一个常量之后，哪天接口的上限改了，这里的校验会自动跟着改。
 *   （代价：setting 模块 import 了 article 模块的一个常量。这是刻意的耦合 ——
 *    这两个值本来就必须一致，把它写成两个数字才是真的错。）
 */
@Data
@Schema(description = "站点设置提交表单")
public class SettingForm {

    @NotBlank(message = "站点名不能为空")
    @Size(max = 50, message = "站点名最长 50 字")
    @Schema(description = "站点名", example = "亿轨星途")
    private String siteName;

    /** 首页公告：可为空（留空 = 前台不显示公告条） */
    @Size(max = 500, message = "公告最长 500 字")
    @Schema(description = "首页公告（可为空）")
    private String announcement;

    /** 评论总开关：true 开启 / false 关闭。用 Boolean 而不是 0/1，见 SiteSetting 的注释 */
    @NotNull(message = "评论开关不能为空")
    @Schema(description = "评论总开关", example = "true")
    private Boolean commentEnabled;

    /** ICP 备案号：可为空（留空 = 页脚不显示备案信息），不做格式校验（理由见类注释） */
    @Size(max = 50, message = "备案号最长 50 字")
    @Schema(description = "ICP 备案号（可为空）", example = "京ICP备12345678号-1")
    private String icpNumber;

    /**
     * 公安网安备案号：可为空（留空 = 页脚不显示公安备案那一行）。
     * 与 {@code icpNumber} 完全同形：同一个长度上限（50，与数据库列长、Service 常量
     * 三处一致）、同样不做格式校验（各省编号规则不同，加正则只会挡住正当输入）、
     * 同样只当文字渲染 —— 链接与备案图标由前端按公安部平台的规则拼。
     * 【为什么它俩不是"二选一"】它们是两套独立的备案体系（主管机关不同、
     * 编号规则不同、指向的查询平台也不同），谁缺了都不影响另一个 ——
     * 两个都填才是正常状态，所以这一栏不能和 icpNumber 共用一个字段。
     */
    @Size(max = 50, message = "公安备案号最长 50 字")
    @Schema(description = "公安网安备案号（可为空）", example = "川公网安备 51090002000169号")
    private String policeNumber;

    /** 页脚版权：可为空（留空 = 页脚不显示版权行） */
    @Size(max = 200, message = "版权文案最长 200 字")
    @Schema(description = "页脚版权（可为空）")
    private String copyright;

    /** 每页文章条数：1 ~ 文章接口的分页上限（理由见类注释） */
    @NotNull(message = "每页条数不能为空")
    @Min(value = 1, message = "每页条数至少 1")
    @Max(value = ArticleQuery.MAX_PAGE_SIZE, message = "每页条数最多 " + ArticleQuery.MAX_PAGE_SIZE + "（接口的分页上限）")
    @Schema(description = "每页文章条数", example = "10")
    private Integer pageSize;
}
