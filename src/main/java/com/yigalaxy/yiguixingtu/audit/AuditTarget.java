package com.yigalaxy.yiguixingtu.audit;

/**
 * 审计的"操作对象类型" —— 这件事是被做在什么东西上的。
 *
 * 【为什么 action 里已经带了对象名（CREATE_ARTICLE），还要单独一个枚举】
 *   两者回答的是不同的问题，用途也不同：
 *     · action 回答"做了什么"，主要是给人看的（页面上直接显示"新建文章"）
 *     · target 回答"做在什么上"，是给【按对象查询】用的：
 *       "这个用户被改动过几次" = 按 target_type + target_id 查，
 *       而不是拿 action 去 LIKE '%USER%' 猜名字
 *   而且 target 是有限且稳定的（文章、用户、标签、评论、分类）：新增一类"被操作的对象"
 *   只是在这里多一个取值，不用再往 action 的命名里塞前缀约定。
 *   —— 这条预判已经兑现过一次：分类的增 / 改 / 删接进审计时，这个枚举只多了一行
 *   {@code CATEGORY}，三个 action 也照原有命名加上 CREATE/UPDATE/DELETE 前缀即可。
 */
public enum AuditTarget {

    /** 文章 */
    ARTICLE,

    /** 用户 */
    USER,

    /** 标签 */
    TAG,

    /** 评论 */
    COMMENT,

    /** 分类 */
    CATEGORY
}
