package com.yigalaxy.yiguixingtu.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 分类信息（对外返回用）。
 *
 * 【为什么要有 VO，不直接返回 Category 实体？】
 * 实体是"数据库长什么样"，VO 是"接口对外长什么样"，两者不该划等号。
 * 比如 deleted / createTime 这些字段前端根本用不上，
 * 而且以后数据库加字段时，不会被迫一起暴露出去。
 */
@Data
@Schema(description = "分类信息")
public class CategoryVO {

    @Schema(description = "分类ID", example = "1")
    private Long id;

    @Schema(description = "分类名称", example = "技术笔记")
    private String name;

    @Schema(description = "分类描述")
    private String description;

    @Schema(description = "排序值", example = "1")
    private Integer sort;
}
