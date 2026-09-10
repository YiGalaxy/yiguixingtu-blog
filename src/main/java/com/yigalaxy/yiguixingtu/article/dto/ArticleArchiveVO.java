package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 归档页的数据（按年月分组）。
 *
 * 【为什么由后端分组，而不是把文章列表甩给前端自己归】
 *   前端拿到的列表是【分页】的，只按当前页分组会得到"每个月只有前几条"的错误结果；
 *   要让前端分对，它就得自己把所有页都拉一遍（N 次请求，而且每页还带着摘要等用不上的字段）。
 *   归档是"一眼看全部"的页面，这个分组天然是数据库擅长的事。
 *
 * 【为什么分成 ArchiveMonth 两层而不是一个扁平列表】
 *   归档页的展示结构就是"月份标题 + 该月的文章"，扁平列表会让前端再归一次。
 *   后端一次给到最终结构，前端只负责渲染 —— 这也是"接口按使用场景设计"的思路。
 */
@Data
@Schema(description = "归档（按年月分组）")
public class ArticleArchiveVO {

    @Schema(description = "归档的月份分组（按时间倒序：最新的在最前面）")
    private List<ArchiveMonth> months = new ArrayList<>();

    /** 归档里的文章总数（方便页面显示"共 N 篇"） */
    @Schema(description = "文章总数")
    private Long total;

    /** 一个月里的文章 */
    @Data
    @Schema(description = "归档中的一个月")
    public static class ArchiveMonth {

        @Schema(description = "年", example = "2026")
        private Integer year;

        @Schema(description = "月", example = "9")
        private Integer month;

        /** 该月的文章数：前端可以用它做"展开/收起"的提示 */
        @Schema(description = "该月文章数", example = "3")
        private Integer count;

        @Schema(description = "该月的文章（按时间倒序）")
        private List<ArchiveArticle> articles = new ArrayList<>();
    }

    /**
     * 归档里的一篇文章。
     *
     * 【为什么只有三个字段】归档页只用来"找到那篇文章"（标题 + 日期 + 链接），
     * 摘要、封面、浏览量都用不上 —— 一篇一堆字段，几百篇就是白白多传几十 KB。
     */
    @Data
    @Schema(description = "归档里的文章条目")
    public static class ArchiveArticle {

        @Schema(description = "文章ID")
        private Long id;

        @Schema(description = "标题")
        private String title;

        @Schema(description = "发布时间")
        private LocalDateTime createTime;
    }
}
