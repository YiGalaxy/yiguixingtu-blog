package com.yigalaxy.yiguixingtu.setting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站点设置实体，对应数据库表 site_setting。
 *
 * 【全站第二张"只有一行"的表，与 About 同形】
 *  主键 {@link IdType#INPUT} 固定为 1、没有 {@code @TableLogic}、只有 updateTime ——
 *  三处的理由与 About 完全一样（"主键由我给"、"删掉设置"这个需求不存在、
 *  单条记录没有"创建"的语义），这里不重复。
 *
 * 【它与 About 的差别只有一处，但那处差别决定了整个模块的形状】
 *  About 是一个"页面"：所有字段一起读、一起写，一次请求拿到的就是一整页内容。
 *  而这张表的字段是【互相独立的开关】：
 *    · 站点名  → 页眉、页脚、标题后缀、og:site_name、RSS 标题
 *    · 公告    → 首页顶部那一条（为空则整块不渲染）
 *    · 评论开关 → 文章页要不要显示评论框
 *    · 页脚两行 → 版权与备案号（各自为空则各自不渲染）
 *    · 每页条数 → 首页请求文章列表时带的 size
 *  所以它们的读法有两种：
 *    ① 前台一次性拿走整份（就这样，别拆成六个接口 —— 每个页面都要用其中
 *       至少一项，拆开只会让首页发六个请求）
 *    ② 后台整份保存（PUT，全量覆盖式提交）
 *
 * 【⚠️ commentEnabled 在这里是 Integer(0/1)，而 DTO/VO 里是 Boolean】
 *  这是刻意的不对称，理由：
 *   · 数据库列是 tinyint，与其它表的 status 字段同形 —— 存 0/1 而不是 'true'/'false'，
 *     运维用 SQL 查"评论是不是被关了"时 `WHERE comment_enabled = 0` 是最自然的写法
 *   · 而接口层是给前端用的，前端那个控件是 el-switch，它的值就是 true/false；
 *     让接口收发布尔，前端就不用写 `:active-value="1"` 这种"把开关硬拧成数字"的代码
 *  转换只发生在一处（Service 的 toVO / 入参归一化），并且两个方向的取值都写了测试。
 */
@Data
@Schema(description = "站点设置")
@TableName("site_setting")
public class SiteSetting implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 这一行固定的主键值（与 About.SINGLE_ROW_ID 同一个约定） */
    public static final long SINGLE_ROW_ID = 1L;

    /** 评论总开关：关闭（前台不显示评论框，接口也拒绝新评论） */
    public static final int COMMENT_DISABLED = 0;

    /** 评论总开关：开启（默认值，与迁移脚本里的 DEFAULT 1 一致） */
    public static final int COMMENT_ENABLED = 1;

    @Schema(description = "固定为 1（这张表只有一行）")
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 站点名，最长 50（与 DTO 校验、数据库列长度三处一致） */
    @Schema(description = "站点名", example = "亿轨星途")
    private String siteName;

    /** 首页公告，可为空（为空时前台整块不渲染，而不是渲染一个空条） */
    @Schema(description = "首页公告（可为空）")
    private String announcement;

    /** 评论总开关：0 关闭 1 开启（接口层是布尔，见类注释） */
    @Schema(description = "评论总开关：0关闭 1开启")
    private Integer commentEnabled;

    /**
     * ICP 备案号：只存号本身（如"京ICP备12345678号-1"），不存链接。
     * 【为什么不校验格式】备案号的形态有好几种（工信部备案、公安联网备案、
     * 各省的编号规则也不完全一样），加一条正则只会挡住正当输入 ——
     * 而它只被当文字渲染，链接是前端写死的工信部地址，没有注入面。
     */
    @Schema(description = "ICP 备案号（可为空）")
    private String icpNumber;

    /** 页脚版权文案，可为空（为空则页脚不显示版权行） */
    @Schema(description = "页脚版权（可为空）")
    private String copyright;

    /** 每页文章条数，取值 1 ~ {@code ArticleQuery.MAX_PAGE_SIZE}（上限跟着接口走，见迁移脚本） */
    @Schema(description = "每页文章条数", example = "10")
    private Integer pageSize;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
