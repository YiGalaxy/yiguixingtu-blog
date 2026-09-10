package com.yigalaxy.yiguixingtu.tag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑标签的提交表单。
 *
 * 【为什么新建和编辑共用一个 DTO】
 *   两者能填的字段完全一样（名字 + 排序），拆成两个类只会多一份重复。
 *   和 ArticleForm 同一个思路，校验注解写在这里，Controller 上加 @Valid 自动生效，
 *   校验失败由 GlobalExceptionHandler 统一转成 400。
 */
@Data
@Schema(description = "标签提交表单")
public class TagForm {

    /**
     * 标签名。
     *
     * 【为什么长度是 1–30】和数据库 tag.name 的 varchar(30) 对齐。
     *   ⚠️ 校验长度必须和列长度一致：只靠数据库报错的话，
     *   用户得到的是一句 "Data too long for column 'name'"，看不懂也帮不上忙；
     *   在这里拦住才能给出"标签名最长 30 字"这种能照着改的提示。
     *
     * 【trim 谁来管】@NotBlank 会拒绝纯空格，但不会去掉首尾空格 ——
     * 而 "Java" 和 "Java " 在唯一索引眼里是两个标签。
     * 所以 Service 里统一 trim 之后再落库（见 TagServiceImpl）。
     */
    @NotBlank(message = "标签名不能为空")
    @Size(max = 30, message = "标签名最长 30 字")
    @Schema(description = "标签名", example = "Spring Boot")
    private String name;

    /** 排序值，越小越靠前；不传时按 0 处理（新建的标签默认排在最后面那批的前面） */
    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;
}
