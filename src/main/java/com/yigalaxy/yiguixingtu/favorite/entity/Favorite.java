package com.yigalaxy.yiguixingtu.favorite.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 收藏实体，对应数据库表 favorite。
 *
 * 【它和友链 / 项目最容易被搞混的一处：category 不是那张 category 表】
 *   `category` 表是【博客文章的栏目】，有唯一索引、删之前还要数"还有几篇文章在用"。
 *   收藏的 `category` 只是【一个自由文本的分组名】（工具 / 文章 / 视频 / 学习资料）：
 *   它不引用任何表，也不做任何校验，空着就是"未分组"。
 *   为什么不让收藏去引用 category 表 —— 完整推导写在 V9 迁移脚本的注释里
 *   （一句话：会更早遇到"删掉一个只被收藏用到的分组之后，收藏的分组名变成查不到的 id"）。
 *
 * 【url 是必填的】一条没有地址的"收藏"没有意义（它不是待办，是"以后还要再来"的入口）——
 *   所以这是几个内容模块里唯一一个 URL 必填的表（项目允许只填仓库）。
 *
 * 【title 不抓取】后端不会去访问目标网页读它的 <title>：
 *   那要发外部请求（SSRF 风险、慢、对方改版就读不到了），
 *   而这张表是站长自己维护的，填的时候顺手写一个更准。
 */
@Data
@Schema(description = "收藏")
@TableName("favorite")
public class Favorite implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：隐藏（前台查不到） */
    public static final int STATUS_HIDDEN = 0;

    /** 状态：显示（前台可见，也是新建时的默认值） */
    public static final int STATUS_VISIBLE = 1;

    @Schema(description = "收藏ID", example = "1")
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标题，最长 100（与 DTO 校验、数据库列长度三处一致） */
    @Schema(description = "标题", example = "MySQL 索引原理图解")
    private String title;

    /** 目标地址，必填；格式由 DTO 上的 @Pattern 白名单保证（只放行 http(s)） */
    @Schema(description = "目标地址", example = "https://example.com/post/1")
    private String url;

    @Schema(description = "备注/说明")
    private String description;

    /**
     * 分组名，自由文本，可为空。
     * ⚠️ 它不是 category 表的 id（那是文章栏目的外键语义）——
     * 这里存的就是"工具"、"文章"这类人写的字。
     */
    @Schema(description = "分组名（自由文本，可为空 = 未分组）", example = "工具")
    private String category;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：0隐藏 1显示")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /** 逻辑删除：本表没有任何唯一索引，所以不会踩"逻辑删除 + 唯一索引"那个坑 */
    @Schema(description = "逻辑删除：0未删 1已删", hidden = true)
    @TableLogic
    private Integer deleted;
}
