package com.yigalaxy.yiguixingtu.favorite.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收藏出参。
 *
 * 【category 在响应里是原样的字符串】
 *   后端不把它转成"分组对象的数组"（那等于把分组逻辑搬到后端），
 *   前端拿到列表之后按 category 分组即可 —— 同组内保持后端给的 sort 顺序。
 *   前端要的"分组下拉建议"也可以直接从这份列表里取 distinct 值，不需要额外接口。
 */
@Data
@Schema(description = "收藏信息")
public class FavoriteVO {

    @Schema(description = "收藏ID", example = "1")
    private Long id;

    @Schema(description = "标题", example = "MySQL 索引原理图解")
    private String title;

    @Schema(description = "目标地址", example = "https://example.com/post/1")
    private String url;

    @Schema(description = "备注/说明")
    private String description;

    @Schema(description = "分组名（可为空 = 未分组）", example = "工具")
    private String category;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：0隐藏 1显示（前台列表里恒为 1）")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
