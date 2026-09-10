package com.yigalaxy.yiguixingtu.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑分类的提交表单。
 *
 * 【为什么长度上限是 50】与数据库 category.name 的 varchar(50) 对齐。
 * 校验长度必须和列长度一致：只靠数据库报错的话，用户得到的是一句
 * `Data too long for column 'name'`，看不懂也帮不上忙；
 * 在这里拦住才能给出"分类名最长 50 字"这种能照着改的提示。
 */
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
