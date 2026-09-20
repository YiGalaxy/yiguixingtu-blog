package com.yigalaxy.yiguixingtu.visit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * =====================================================================
 * 一条访问记录（后台「访问统计」的数据来源，建表见 V17）
 *
 * 【它和 Article.viewCount 的分工，别当成重复】
 *   viewCount 是**每篇文章一个累计数字**，回答"这篇总共被看了多少次"；
 *   这张表是**一条一条的访问**，回答"每天多少人来、都是谁、从哪来"。
 *   两者并存：累计数字给不出曲线，明细也给不出"一眼看到的总量"。
 *
 * 【为什么字段全是平平无奇的字符串/数字，没有枚举类型】
 *   这几个维度（身份 / 来源 / 设备）都是**统计口径**，将来可能加档
 *   （比如"平板"、比如"搜索引擎"单独一类）。用枚举的话，加一档要动实体、
 *   要处理"库里存着旧值"的兼容；用字符串 + 常量，加一档只是多一个分支。
 *   ⚠️ 代价是写库时没有编译期检查 —— 所以取值统一由下面那几个常量给出，
 *   不在业务代码里手写字符串字面量。
 * =====================================================================
 */
@Data
@Schema(description = "访问记录")
@TableName("visit_log")
public class VisitLog implements Serializable {

    private static final long serialVersionUID = 1L;

    // ---------- 访客身份 ----------

    /** 游客：没带 token */
    public static final String VISITOR_GUEST = "GUEST";

    /** 注册用户 */
    public static final String VISITOR_USER = "USER";

    /** 管理员 */
    public static final String VISITOR_ADMIN = "ADMIN";

    // ---------- 来源 ----------

    /** 站内跳转（来源主机就是本站） */
    public static final String REFERER_INTERNAL = "INTERNAL";

    /** 外链（别的站点链过来的） */
    public static final String REFERER_EXTERNAL = "EXTERNAL";

    /** 直接访问（没有 Referer：书签、直接输地址、从客户端打开） */
    public static final String REFERER_DIRECT = "DIRECT";

    // ---------- 设备 ----------

    /** 电脑 */
    public static final String DEVICE_PC = "PC";

    /** 手机（含平板：只分两档，见 V17 的说明） */
    public static final String DEVICE_MOBILE = "MOBILE";

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 访问路径（**不含查询串**：查询串里往往带着用户的搜索词，属于另一类隐私） */
    @Schema(description = "访问路径")
    private String path;

    /** 文章页才有：文章 id（用来算"哪几篇最热"） */
    @Schema(description = "文章 id（非文章页为 null）")
    private Long articleId;

    /** 见上面 VISITOR_* 常量 */
    @Schema(description = "访客身份：GUEST / USER / ADMIN")
    private String visitorType;

    /** 登录用户的账号 id（游客为 null） */
    @Schema(description = "账号 id（游客为 null）")
    private Long userId;

    /**
     * 来源 IP。
     * ⚠️ 它是个人信息，随记录一起在保留期到期后删除（见 VisitLogCleanupTask）。
     */
    @Schema(description = "来源 IP")
    private String ip;

    /**
     * IP 归属地（如 {@code 江苏省 南京市}）。
     *
     * 【它是从 ip 推导出来的缓存，不是独立的一份数据】写入时由
     * {@link com.yigalaxy.yiguixingtu.common.web.IpRegionResolver} 解析一次落库，
     * 后台的访客列表直接读它 —— 不必为了显示一列而对每一行再查一次库。
     * 和 device（从 UA 推导）、refererType（从 Referer 推导）是同一个套路。
     *
     * ⚠️ 【它的生命周期必须和 ip 完全一致】它是从 IP 推出来的、颗粒度到城市 ——
     *   知道某人某天在深圳，和知道他的 IP，对定位一个人的帮助没有量级差别。
     *   所以它**不是**"脱敏后的安全字段"：保留期到期时随整行一起删除
     *   （清理任务删的是整行，不需要为它单独做清理）。
     *
     * 为 null 的两种情况：① 这一列存在之前的历史记录；② 解析不出
     *   （内网地址会给「内网」，但 IPv6 与畸形值给 null）。见 V19 的注释。
     */
    @Schema(description = "IP 归属地（写入时从 ip 推导；解析不出为 null）")
    private String region;

    /** 见上面 REFERER_* 常量 */
    @Schema(description = "来源类型：INTERNAL / EXTERNAL / DIRECT")
    private String refererType;

    /** 外链时的来源主机名（只存主机名，不存完整地址） */
    @Schema(description = "来源主机名（仅外链）")
    private String refererHost;

    /** 见上面 DEVICE_* 常量 */
    @Schema(description = "设备：PC / MOBILE")
    private String device;

    /**
     * 访问时间。
     * 【为什么由应用显式赋值，而不是靠列上的 DEFAULT】与 Comment.createTime 同一条理由：
     * 插入后要拿这条记录去算聚合，而 MyBatis-Plus 不会把库生成的值回填到对象上。
     */
    @Schema(description = "访问时间")
    private LocalDateTime createTime;
}
