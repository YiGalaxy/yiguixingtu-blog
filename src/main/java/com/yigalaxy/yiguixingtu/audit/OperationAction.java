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
    DELETE_USER("删除用户"),

    /** 新建标签 */
    CREATE_TAG("新建标签"),

    /** 编辑标签（改名 / 改排序） */
    UPDATE_TAG("编辑标签"),

    /** 删除标签 */
    DELETE_TAG("删除标签"),

    /** 审核评论（通过 / 拒绝） */
    UPDATE_COMMENT_STATUS("审核评论"),

    /** 删除评论 */
    DELETE_COMMENT("删除评论"),

    /** 新建分类 */
    CREATE_CATEGORY("新建分类"),

    /** 编辑分类 */
    UPDATE_CATEGORY("编辑分类"),

    /** 删除分类 */
    DELETE_CATEGORY("删除分类"),

    /**
     * 新建友链。
     *
     * 【这一组为什么是"内容类"而不是"文章类"】
     *   友链 / 项目 / 收藏 / 关于（F5 的四个内容模块）都是【站点级静态内容】：
     *   管理员本人维护、没有别的表引用它们、也不需要审核。
     *   它们各自的增删改都记一笔，命名沿用 {@code 动作_对象} 的既有规则。
     */
    CREATE_LINK("新建友链"),

    /** 编辑友链 */
    UPDATE_LINK("编辑友链"),

    /** 删除友链 */
    DELETE_LINK("删除友链"),

    /** 新建项目 */
    CREATE_PROJECT("新建项目"),

    /** 编辑项目 */
    UPDATE_PROJECT("编辑项目"),

    /** 删除项目 */
    DELETE_PROJECT("删除项目"),

    /** 新建收藏 */
    CREATE_FAVORITE("新建收藏"),

    /** 编辑收藏 */
    UPDATE_FAVORITE("编辑收藏"),

    /** 删除收藏 */
    DELETE_FAVORITE("删除收藏"),

    /**
     * 保存关于页信息。
     *
     * 【为什么只有 UPDATE 没有 CREATE / DELETE】
     *   关于页是全站唯一一份单条数据：那一行由迁移脚本插好、
     *   也不会被删除（不要了就清空字段）。所以这一族动作只有一个 ——
     *   枚举里的取值和真实存在的动作一一对应，不留"将来可能用到"的空位。
     */
    UPDATE_ABOUT("保存关于页信息");

    /** 中文说明，便于直接展示与排查（不用在前端再维护一份翻译） */
    private final String description;

    OperationAction(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
