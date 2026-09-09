package com.yigalaxy.yiguixingtu.auth.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登录返回")
public class LoginVO {

    @Schema(description = "token，后续请求放请求头 Authorization: Bearer <token>")
    private String token;

    @Schema(description = "用户ID", example = "1")
    private Long id;

    @Schema(description = "用户名", example = "yigalaxy")
    private String username;

    @Schema(description = "昵称", example = "意咖")
    private String nickname;

    @Schema(description = "角色：ADMIN管理员/GUEST游客", example = "ADMIN")
    private String role;
}
