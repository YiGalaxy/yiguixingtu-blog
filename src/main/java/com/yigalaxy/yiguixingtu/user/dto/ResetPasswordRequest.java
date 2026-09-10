package com.yigalaxy.yiguixingtu.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置密码请求。
 *
 * 【为什么密码放请求体，而不是 URL 参数？】
 * URL 参数会出现在浏览器历史、Nginx 访问日志、代理日志里，
 * 密码写在 URL 上等于到处留痕。放请求体最安全。
 */
@Data
@Schema(description = "重置密码请求")
public class ResetPasswordRequest {

    @Schema(description = "新密码", example = "123456")
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度需要在6-20之间")
    private String password;
}
