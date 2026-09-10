package com.yigalaxy.yiguixingtu.audit;

/**
 * 操作类型枚举。
 *
 * 【为什么要用枚举而不是随手写字符串】
 *   审计数据是要被【查询和统计】的（"最近一周有几次改角色"），
 *   如果 action 是散落各处的字符串字面量，拼错一个字母就会出现一个
 *   永远不会被统计到的分类，而且不会有任何报错。
 *   枚举把取值收在一处，编译器还能帮你检查拼写。
 *
 * 【为什么存到数据库时用 name() 而不是 ordinal()】
 *   ordinal 是枚举的定义顺序，往中间插一个新值就会让所有历史数据错位。
 *   name 是字符串，插值不影响已有数据。
 *
 * 【命名规则】动作_对象，例如 UPDATE_ARTICLE、DELETE_USER。
 *   这样按前缀分组统计（"所有 UPDATE_*"）也很方便。
 */
public enum OperationAction {

    /** 新建文章 */
    CREATE_ARTICLE("新建文章"),

    /** 编辑文章 */
    UPDATE_ARTICLE("编辑文章"),

    /** 发布 / 下架文章 */
    UPDATE_ARTICLE_STATUS("发布或下架文章"),

    /** 删除文章 */
    DELETE_ARTICLE("删除文章"),

    /** 启用 / 禁用用户 */
    UPDATE_USER_STATUS("启用或禁用用户"),

    /** 修改用户角色 */
    UPDATE_USER_ROLE("修改用户角色"),

    /** 重置用户密码 */
    RESET_USER_PASSWORD("重置用户密码"),

    /** 删除用户 */
    DELETE_USER("删除用户");

    /** 中文说明，便于直接展示与排查（不用在前端再维护一份翻译） */
    private final String description;

    OperationAction(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
