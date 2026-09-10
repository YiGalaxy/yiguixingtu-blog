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

    /**
     * 【这两个码只用于"请求本身有问题"，不用于业务判断】
     *
     * ENDPOINT_NOT_FOUND：地址写错了 / 这个接口不存在。
     *   它和 ARTICLE_NOT_FOUND（文章不存在）是两回事：
     *   前者是"你请求的这个地址根本没有"，后者是"地址对、但里面那篇文章没有"。
     *   混在一起的后果很具体：调前端接口时看到 404，
     *   分不清是自己把地址拼错了，还是数据真的没了。
     *
     * METHOD_NOT_ALLOWED：地址对、但 HTTP 方法用错了
     *   （比如用一个只支持 GET 的接口去 POST）。
     *   不单独区分的话，它会被兜底处理器报成 500"服务器内部错误"——
     *   于是明明是自己调用姿势不对，却看起来像后端崩了。
     */
    ENDPOINT_NOT_FOUND(404, "接口不存在"),

    METHOD_NOT_ALLOWED(405, "请求方法不支持"),

    /**
     * 同一个请求正在处理中（带着相同的 Idempotency-Key 又发了一次）。
     *
     * 【为什么用 429 而不是 400】
     *   429 是 HTTP 标准里"你请求太频繁了"的语义，正好对应这个场景：
     *   不是参数错了，而是【同样的请求来得太密】。
     *   前端看到它最常见的处理就是提示"请勿重复提交，稍候"。
     */
    DUPLICATE_SUBMIT(429, "请求正在处理中，请勿重复提交"),

    USERNAME_EXISTS(400,"账号已存在"),

    USER_NOT_FOUND(404,"用户不存在"),

    ARTICLE_NOT_FOUND(404, "文章不存在"),

    CATEGORY_NOT_FOUND(404, "分类不存在");


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
