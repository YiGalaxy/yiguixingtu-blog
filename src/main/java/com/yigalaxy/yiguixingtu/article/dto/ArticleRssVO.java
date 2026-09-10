package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSS 订阅源里的一篇文章。
 *
 * 【为什么带正文，而列表 VO 不带】
 *   RSS 读者（Feedly、Inoreader 之类）的典型用法是"在阅读器里直接读完"，
 *   所以正文是它最核心的字段 —— 只给摘要的话，读者还得点回站点。
 *   而 /article/page 那种列表接口刻意不带正文（longtext 可能几十 KB，
 *   一页 10 篇纯属浪费），两者用途不同，字段自然也不同。
 *
 * 【为什么由后端一次性给出，而不是让前端逐篇去查】
 *   RSS 通常要最近 20 篇的正文。让前端拿 id 逐篇调 /article/{id} 就是 20 次请求，
 *   而这里一条 SQL 就够 —— 这也是"接口按使用场景设计"的又一个例子。
 *
 * 【为什么没有正文的渲染结果】正文是 Markdown 源码，转换成 HTML 是渲染层的事
 *   （前端有 Markdown 渲染器）。后端只给源码，RSS 里需要 HTML 时由前端转 ——
 *   否则同一份 Markdown 的渲染规则就被复制到了两个仓库里。
 */
@Data
@Schema(description = "RSS 订阅源里的文章条目")
public class ArticleRssVO {

    @Schema(description = "文章ID（前端用它拼出详情页链接）")
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "摘要")
    private String summary;

    @Schema(description = "正文（Markdown 源码）")
    private String content;

    @Schema(description = "发布时间（RSS 的 pubDate 用它）")
    private LocalDateTime createTime;
}
