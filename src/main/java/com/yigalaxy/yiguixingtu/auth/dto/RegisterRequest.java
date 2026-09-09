package com.yigalaxy.yiguixingtu.auth.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "注册请求")
public class RegisterRequest {


    @Schema(description = "用户名",example = "yigalaxy")
    @NotBlank(message = "用户名不能为空")
    private String username;

    @Schema(description = "密码",example = "123456")
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度需要在6-20之间")
    private String password;

    @Schema(description = "昵称", example = "小亿同学")
    private String nickname;
}
