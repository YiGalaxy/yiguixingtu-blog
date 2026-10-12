package com.yigalaxy.yiguixingtu.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新建或编辑分类的表单。 */
@Data
@Schema(description = "分类提交表单")
public class CategoryForm {

    @NotBlank(message = "分类名不能为空")
    @Size(max = 50, message = "分类名最长 50 字")
    @Schema(description = "分类名称", example = "技术笔记")
    private String name;

    @Size(max = 255, message = "分类描述最长 255 字")
    @Schema(description = "分类描述")
    private String description;

    /** 排序值，越小越靠前；不传时按 0 处理 */
    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;
}
