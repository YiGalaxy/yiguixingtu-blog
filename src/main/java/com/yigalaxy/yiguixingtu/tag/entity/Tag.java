package com.yigalaxy.yiguixingtu.tag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 标签实体，对应数据库表 tag。
 *
 * 【⚠️ 它没有 @TableLogic / deleted 字段，和 User / Article / Category 都不一样】
 *   这是刻意的，理由写在 V5__create_tag_tables.sql 的注释里，这里说结论：
 *   逻辑删除与唯一索引（uk_name）天生打架 —— 删掉的物理行仍占着名字，
 *   于是"删掉标签 A 再建同名 A"会撞唯一索引，而带 @TableLogic 的查重又看不见那行。
 *   User 表当时靠"删除时改写用户名"绕过，但标签不值得付那个代价
 *   （名字会被污染成 "技术#deleted#7"），而且标签本来就允许"删了重建"。
 *   所以这里用物理删除：唯一索引语义干净、没有幽灵行；删除动作本身
 *   由审计表（operation_log 的 DELETE_TAG）留痕。
 *
 * 【为什么它和 Category 不是一张表】
 *   两者是不同维度的东西，面试里也常被问：
 *     · 分类是【一对一】：一篇文章属于一个分类（article.category_id），是树的形状
 *     · 标签是【多对多】：一篇文章可以有多个标签，一个标签下有很多文章
 *       （靠 article_tag 关联表实现）
 *   硬合成一张表，要么丢掉多对多能力，要么给 category_id 塞逗号分隔的字符串
 *   （那样就没法按标签建索引、也没法统计标签下的文章数）。
 */
@Data
@Schema(description = "文章标签")
@TableName("tag")
public class Tag implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "标签ID", example = "1")
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标签名，唯一；长度上限 30（见 V5 里对 varchar(30) 的解释） */
    @Schema(description = "标签名", example = "Spring Boot")
    private String name;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
