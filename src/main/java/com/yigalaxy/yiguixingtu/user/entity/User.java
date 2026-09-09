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