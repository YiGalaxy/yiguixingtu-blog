package com.yigalaxy.yiguixingtu.user.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户分页查询条件。
 *
 * 【为什么用对象接收，而不是一堆 @RequestParam？】
 * 前端 GET 请求的参数（page/size/keyword/role/status）会由 Spring 自动
 * 按字段名塞进这个对象里，Controller 方法签名很干净，以后加参数也不用改签名。
 * 这就是"用一个对象装查询条件"的常见做法。
 */
@Data
@Schema(description = "用户分页查询条件")
public class UserQuery {

    @Schema(description = "页码，从1开始", example = "1")
    private Long page = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long size = 10L;

    @Schema(description = "关键词：模糊匹配 用户名 或 昵称")
    private String keyword;

    @Schema(description = "角色筛选：ADMIN / GUEST")
    private String role;

    @Schema(description = "状态筛选：1正常 0禁用")
    private Integer status;
}