package com.yigalaxy.yiguixingtu.common;

/**
 * 结果码枚举
 */
public enum ResultCode {

    SUCCESS(200, "成功"),

    BAD_CREDENTIALS(400, "用户名或密码错误"),

    PARAM_ERROR(400, "参数校验失败"),

    UNAUTHORIZED(401, "未登录或登录已过期"),

    FORBIDDEN(403, "无权限访问"),

    ACCOUNT_DISABLED(403, "账号已被禁用"),

    ERROR(500, "服务器内部错误"),

    USERNAME_EXISTS(400,"账号已存在");



    /**
     * 状态码
     */
    private final int code;

    /**
     * 提示信息
     */
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
