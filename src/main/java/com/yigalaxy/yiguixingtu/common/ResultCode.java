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

    /**
     * 被限流了（应用层的 Resilience4j 或 Nginx 拦下了多余请求）。
     *
     * 【为什么和 DUPLICATE_SUBMIT 共用 429，但文案不同】
     *   429 在 HTTP 里的语义就是"请求太多了"，这两个场景都符合：
     *     · DUPLICATE_SUBMIT —— 同一个提交动作来得太密（幂等键还在处理中）
     *     · TOO_MANY_REQUESTS —— 这个接口整体被刷得太狠
     *   状态码相同、message 分开，前端可以分别提示"请勿重复提交"和"稍后再试"。
     */
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试"),

    USERNAME_EXISTS(400,"账号已存在"),

    USER_NOT_FOUND(404,"用户不存在"),

    ARTICLE_NOT_FOUND(404, "文章不存在"),

    CATEGORY_NOT_FOUND(404, "分类不存在"),

    TAG_NOT_FOUND(404, "标签不存在"),

    COMMENT_NOT_FOUND(404, "评论不存在"),

    /**
     * 友链不存在（包含"已被逻辑删除"的情况）。
     * 【为什么每个模块各有一个 NOT_FOUND，而不是共用一个"数据不存在"】
     *   前端要提示的是"这条友链可能已经被删掉了，刷新一下"，
     *   而用户看到的页面决定了这句话说不说得通 —— 统一的"数据不存在"等于没说。
     *   和 USER_NOT_FOUND / ARTICLE_NOT_FOUND 一样各说各的，状态码都是 404。
     */
    LINK_NOT_FOUND(404, "友链不存在"),

    /** 项目不存在（含"已被逻辑删除"） */
    PROJECT_NOT_FOUND(404, "项目不存在"),

    /** 分类名重复（前端要提示"换个名字"，而不是笼统的"已存在"） */
    CATEGORY_NAME_EXISTS(400, "分类名已存在"),

    /**
     * 分类还有文章在用，不能删。
     * 【为什么不是 409 Conflict】本项目的业务错误统一 400（HTTP 仍是 200 + body.code），
     * 这里沿用同一套；真要细分冲突语义，那是 5.6 那个"统一状态码"的破坏性改动要一起做的事。
     */
    CATEGORY_IN_USE(400, "分类下还有文章，不能删除"),

    /**
     * 标签名重复。
     * 【为什么和 USERNAME_EXISTS 分开而不是共用一个"已存在"】
     *   前端要提示的是"标签名已存在，换一个"，而不是笼统的"已存在"——
     *   用户在标签管理页面上看到后者会不知道是哪一项冲突了。
     *   状态码都是 400（语义是"你提交的内容有问题"），message 各说各的。
     */
    TAG_NAME_EXISTS(400, "标签名已存在");


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
