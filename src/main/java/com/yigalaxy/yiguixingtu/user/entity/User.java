package com.yigalaxy.yiguixingtu.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体，对应数据库表 user
 */
@Data
@Schema(description = "用户信息")
@TableName("user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户ID", example = "1")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "登录账号", example = "yigalaxy")
    private String username;

    @Schema(description = "密码(BCrypt哈希)", hidden = true)
    private String password;

    @Schema(description = "昵称", example = "意咖")
    private String nickname;

    /**
     * 邮箱。注册时必填（见 RegisterRequest 上的 @NotBlank + @Email）。
     *
     * 【为什么实体里允许为 null，而注册接口要求必填】
     *   这一列是 2026-09 才加的，**存量账号没有邮箱**，也不可能凭空补 ——
     *   所以数据库列可空，必填只加在注册入口上。`email == null` 有且只有一个
     *   来源：这一列存在之前注册的老账号。
     *
     * 【为什么没有唯一索引】本期不发验证邮件，"这个邮箱真的是他的"从未被验证过，
     *   一个没验证过的值不能当身份键。详见 V18 迁移脚本里的说明。
     */
    @Schema(description = "邮箱（注册必填；老账号可能为 null）", example = "someone@example.com")
    private String email;

    @Schema(description = "角色：ADMIN管理员 / GUEST游客", example = "ADMIN")
    private String role;

    @Schema(description = "状态：1正常 0禁用", example = "1")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0未删 1已删", hidden = true)
    @TableLogic
    private Integer deleted;
}