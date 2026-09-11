package com.yigalaxy.yiguixingtu.link.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 友链出参。
 *
 * 【为什么返回 VO 而不是 FriendLink 实体】
 *   这是项目里贯彻到所有模块的一条约定（README「接口安全约定」：出参一律用 VO）。
 *   实体身上带着"数据库的形状"：`deleted` 这种内部字段不该出现在接口里。
 *   VO 是"接口的形状"，将来数据库加列（比如加一个"友链 RSS 地址"），
 *   只要不改 VO，前端就完全不受影响 —— 反过来直接把实体吐出去，
 *   加一列等于自动改了接口契约。
 *
 * 【前台列表里 status 永远是 1，为什么还要带这个字段】
 *   因为这个 VO 被前台和后台【共用】（同一个 Service 的两个读方法）。
 *   后台需要一个"显示/隐藏"的列，前台那里它恒定是 1、前端可以直接忽略。
 *   为它单独再建一个 VO 的代价（两个类要保持同步）比多传一个字段大得多 ——
 *   和 TagVO 里带 sort 是同一个取舍。
 */
@Data
@Schema(description = "友链信息")
public class FriendLinkVO {

    @Schema(description = "友链ID", example = "1")
    private Long id;

    @Schema(description = "站点名称", example = "某某的博客")
    private String name;

    @Schema(description = "站点地址", example = "https://example.com")
    private String url;

    @Schema(description = "头像地址")
    private String avatar;

    @Schema(description = "一句话介绍")
    private String description;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：0隐藏 1显示（前台列表里恒为 1）")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
