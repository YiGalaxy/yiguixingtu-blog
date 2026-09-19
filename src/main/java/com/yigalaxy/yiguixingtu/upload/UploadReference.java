package com.yigalaxy.yiguixingtu.upload;

/**
 * 一条"某个上传文件被谁引用了"的记录（后台「存储管理」页展示用）。
 *
 * 【它和 UploadReferenceChecker 是什么关系 —— 说清楚，免得被当成重复实现】
 *   · {@link UploadReferenceChecker} 回答的是"**能不能删**"（布尔），
 *     它是删除路径上唯一的权威判据，用的是每张表各自 Mapper 上那条按 key 精确
 *     匹配的 SQL（LOCATE + 索引无关，但每次只问一个 key）。
 *   · 这个类型是{@link UploadReferenceIndex **展示用**的反向索引}的元素：
 *     它一次把"全站所有引用"都读出来（每张表一条 SQL），用来在列表页上显示
 *     "这张图被谁用了"。
 *   两者读的是**同一批表和列**（清单在两边的注释里互相指着），
 *   但用途不同：判据永远用 Checker，展示才用 Index。用反了的后果：
 *   把 Index 当判据 → 它多读了几列（比如正文里的说明文字）时会挡住删除；
 *   把 Checker 当展示 → 它返回不了"是谁"。
 *
 * @param type     引用方是哪一类（{@code ARTICLE} / {@code ATTACHMENT} / {@code MUSIC} /
 *                 {@code ABOUT} / {@code FRIEND_LINK} / {@code PROJECT} / {@code FAVORITE}）。
 *                 ⚠️ 用字符串而不是枚举：它是给前端显示与拼跳转链接用的，
 *                 前端拿它当不透明标签即可（枚举序列化过去也是同一个字符串，
 *                 但多一个跨模块的枚举类型要同步维护）
 * @param targetId 引用方的 id，**前端拿它拼跳转链接**（文章→{@code /article/{id}}、
 *                 音乐→{@code /music}……）。附件那条是它所属文章的 id
 * @param title    引用方的标题（文章标题 / 曲名 / 友链名 / 附件名 / 项目名），可能为空
 * @param field    命中在**哪一列**（"封面" / "正文" / "摘要" / "头像" / "音源"……）。
 *                 同一个文件被同一篇文章引用两次（封面 + 正文）时会出现两条记录，
 *                 靠这个字段区分 —— 只显示"被 N 处引用"的话，站长看到数字却找不到地方
 */
public record UploadReference(String type, Long targetId, String title, String field) {
}
