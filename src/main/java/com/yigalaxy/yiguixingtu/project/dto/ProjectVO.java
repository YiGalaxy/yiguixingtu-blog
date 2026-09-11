package com.yigalaxy.yiguixingtu.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目出参。
 *
 * 【为什么前台和后台共用这一个 VO（而不是拆两个）】
 *   两边要的字段在这里完全一样：前台渲染卡片需要 name/description/url/repo/cover/tech/sort，
 *   后台多看一眼 status 与 createTime。为一个"显示/隐藏"的列再建一个 VO，
 *   代价是两个类要保持同步（加一个字段要改两处），收益接近于零。
 *   —— 真正需要拆两个 VO 的场景是"前台绝不能看到某个字段"（比如评论的 email / ip，
 *     那里用的是 AdminCommentVO），项目没有这种字段。
 */
@Data
@Schema(description = "项目信息")
public class ProjectVO {

    @Schema(description = "项目ID", example = "1")
    private Long id;

    @Schema(description = "项目名称", example = "亿轨星途博客系统")
    private String name;

    @Schema(description = "项目简介")
    private String description;

    @Schema(description = "在线演示地址")
    private String url;

    @Schema(description = "代码仓库地址")
    private String repo;

    @Schema(description = "封面图地址")
    private String cover;

    @Schema(description = "技术栈（逗号分隔）", example = "Spring Boot,MySQL,Redis")
    private String tech;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：0隐藏 1显示（前台列表里恒为 1）")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
