package com.yigalaxy.yiguixingtu.upload;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * =====================================================================
 * 上传成功的返回体：{@code { url, name, size }}
 *
 * 【为什么从"直接返回一个 URL 字符串"改成这个对象】
 *   这个接口上线时返回的是 {@code {"url": "..."}}（一个 Map）。
 *   现在前端需要两样新东西才能渲染附件列表：
 *     · {@code name} —— 列表里显示"毕业论文.pdf"，而不是那串 UUID 地址
 *     · {@code size} —— 显示"12.3 MB"，让用户知道点下去要下多大
 *   于是把这两个字段一起返回。
 *
 *   ⚠️ 这【不是一次破坏性改动】：原来读 {@code data.url} 的代码一行都不用改
 *   （字段名和位置都没变，只是多了两个兄弟字段）。前端的上传组件可以
 *   平滑升级：老组件继续只用 url，新组件顺带读 name/size。
 *   这正是当初"不直接返回字符串、而是返回一个对象"那个决定兑现的地方 ——
 *   当时 Controller 的注释就写着"以后想加字段不用改前端的解析代码"。
 *
 * 【为什么用 record 而不是常规的 VO 类】
 *   它是一个【不可变的结果快照】：三个字段，生成后没有任何理由被改。
 *   record 一行给你构造器、getter（这里是 url()/name()/size()）和 equals/hashCode，
 *   而且天然是 final 的 —— 不会有人给它加一个 setter 然后把返回体改花。
 *   项目里的 DTO 之所以用 @Data 的类，是因为它们要与表单/JSON 双向绑定
 *   （需要无参构造 + setter）；这个对象只出不进，所以不需要那些。
 *   Jackson 与 springdoc 都能直接处理 record，不用额外配任何东西。
 *
 * 【size 为什么是 long 而不是 int】
 *   附件单文件上限是 100MB（104857600），int 装得下；但"字节数"这个语义
 *   在 Java 里用 long 是通行做法，而且这个值会写进 article_attachment.size
 *   （那一列是 bigint）。两处类型一致，就不会在某天放宽上限时突然溢出。
 * =====================================================================
 *
 * @param url  可直接访问的完整地址（形如 http://host/uploads/attachment/2026/09/{uuid}.pdf）
 * @param name 展示用的文件名（上传时的原始名，去掉路径、截断到 100 字；见 UploadService.sanitizeName）
 * @param size 文件字节数
 */
@Schema(description = "上传结果")
public record UploadResult(

        @Schema(description = "可访问的完整地址")
        String url,

        @Schema(description = "展示用的文件名（上传时的原始文件名）", example = "毕业论文.pdf")
        String name,

        @Schema(description = "文件大小（字节）", example = "1258291")
        long size) {
}
