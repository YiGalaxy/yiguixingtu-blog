package com.yigalaxy.yiguixingtu.article.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章实体，对应数据库表 article
 */
@Data
@Schema(description = "文章")
@TableName("article")
public class Article implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "文章ID", example = "1")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "摘要（列表页展示）")
    private String summary;

    @Schema(description = "正文（Markdown 源码）")
    private String content;

    @Schema(description = "封面图URL")
    private String cover;

    @Schema(description = "分类ID")
    private Long categoryId;

    /** 0=草稿 1=已发布 */
    @Schema(description = "状态：0草稿 1已发布", example = "0")
    private Integer status;

    @Schema(description = "浏览量", example = "0")
    private Integer viewCount;

    /**
     * 是否置顶：0否 1是
     *
     * 【为什么用 Integer 而不是 boolean？】
     * 如果写成 boolean isTop，Lombok 生成的是 isTop() 而不是 getIsTop()，
     * MyBatis-Plus 推断字段名时容易把属性认成 top，映射到数据库就找不到 is_top 列。
     * 用 Integer 生成的是 getIsTop()/setIsTop()，字段名推断百分百正确。
     * 这是 MP + Lombok 的一个经典坑，凡是 isXxx 的列都建议用包装类型。
     */
    @Schema(description = "是否置顶：0否 1是", example = "0")
    private Integer isTop;

    @Schema(description = "作者用户ID")
    private Long authorId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0未删 1已删", hidden = true)
    @TableLogic
    private Integer deleted;
}
