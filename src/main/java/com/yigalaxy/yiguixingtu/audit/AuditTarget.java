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
 *   而且 target 是有限且稳定的（文章、用户），将来加"分类管理"的审计时
 *   只是多一个取值，不用再往 action 的命名里塞前缀约定。
 */
public enum AuditTarget {

    /** 文章 */
    ARTICLE,

    /** 用户 */
    USER,

    /** 标签 */
    TAG
}
