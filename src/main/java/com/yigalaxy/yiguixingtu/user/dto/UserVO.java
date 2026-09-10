package com.yigalaxy.yiguixingtu.user.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息出参 VO。
 *
 * 【为什么要有 VO，而不是直接返回 User 实体？】
 * User 实体里有 password（BCrypt哈希）和 deleted 字段，
 * 如果直接返回给前端，密码哈希就泄露了 —— 这是非常典型的低级安全事故。
 * VO 只放"前端该看到"的字段，从源头上杜绝泄露。
 */

@Data
@Schema(description = "用户信息（不含密码）")
public class UserVO {
    @Schema(description = "用户ID", example = "1")
    private Long id;

    @Schema(description = "登录账号", example = "yigalaxy")
    private String username;

    @Schema(description = "昵称", example = "意咖")
    private String nickname;

    @Schema(description = "角色：ADMIN管理员 / GUEST游客", example = "GUEST")
    private String role;

    @Schema(description = "状态：1正常 0禁用", example = "1")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
