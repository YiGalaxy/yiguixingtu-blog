package com.yigalaxy.yiguixingtu.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 项目实体，对应数据库表 project。
 *
 * 【它和友链（FriendLink）的形状几乎一样，差别只在字段】
 *   两者都是"站点级静态内容"：管理员自己维护、没有别的表引用、
 *   靠 status 控制显不显示、用逻辑删除、缓存用同一个内容版本号。
 *   真正不同的只有一点：项目的 url 与 repo【可以各为空、但不能同时为空】——
 *   这条规则跨两个字段，所以只能写在 Service 里（见 ProjectServiceImpl）。
 *
 * 【tech 为什么是字符串而不是集合】
 *   它是纯展示字段（前端用 split(',') 渲染成一行小标签）。
 *   做成 project_tech 关联表要多一套"覆盖式重写关联"的逻辑，
 *   而我们没有任何"按技术栈筛选 / 统计"的需求 —— 理由写在 V8 迁移脚本里。
 */
@Data
@Schema(description = "项目")
@TableName("project")
public class Project implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：隐藏（前台查不到） */
    public static final int STATUS_HIDDEN = 0;

    /** 状态：显示（前台可见，也是新建时的默认值） */
    public static final int STATUS_VISIBLE = 1;

    @Schema(description = "项目ID", example = "1")
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 项目名称，最长 100（与 DTO 校验、数据库列长度三处一致） */
    @Schema(description = "项目名称", example = "亿轨星途博客系统")
    private String name;

    @Schema(description = "项目简介")
    private String description;

    /** 在线演示地址，可空；与 repo 不能同时为空（规则在 Service 里） */
    @Schema(description = "在线演示地址")
    private String url;

    /** 代码仓库地址，可空；与 url 不能同时为空 */
    @Schema(description = "代码仓库地址")
    private String repo;

    /** 封面图，可空（没有封面时前端用"渐变底 + 首字母"兜底） */
    @Schema(description = "封面图地址")
    private String cover;

    /** 技术栈，逗号分隔（纯展示，如 "Spring Boot,MySQL,Redis"） */
    @Schema(description = "技术栈（逗号分隔）", example = "Spring Boot,MySQL,Redis")
    private String tech;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：0隐藏 1显示")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记。
     * project 表上没有任何唯一索引，所以它不会撞上"逻辑删除 + 唯一索引"那个坑
     * （那个坑是 tag / category / user 三张表都踩过的，见 V5 与 CategoryServiceImpl 的注释）。
     */
    @Schema(description = "逻辑删除：0未删 1已删", hidden = true)
    @TableLogic
    private Integer deleted;
}
