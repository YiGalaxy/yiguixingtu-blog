package com.yigalaxy.yiguixingtu.category.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分类实体，对应数据库表 category
 */
@Data
@Schema(description = "文章分类")
@TableName("category")
public class Category implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "分类ID", example = "1")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "分类名称", example = "技术笔记")
    private String name;

    @Schema(description = "分类描述")
    private String description;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0未删 1已删", hidden = true)
    @TableLogic
    private Integer deleted;
}