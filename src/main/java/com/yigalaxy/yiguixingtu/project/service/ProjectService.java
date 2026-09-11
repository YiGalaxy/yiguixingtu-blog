package com.yigalaxy.yiguixingtu.project.service;

import com.yigalaxy.yiguixingtu.project.dto.ProjectForm;
import com.yigalaxy.yiguixingtu.project.dto.ProjectVO;

import java.util.List;

/**
 * 项目服务。
 *
 * 【读法两个、写法三个，和友链完全同形】
 *   · {@link #listVisible()} —— 前台公开（SecurityConfig 放行了 GET /project/list），
 *     只返回 status = 1，走 Redis 缓存（key 里带内容缓存版本号）
 *   · {@link #listAll()} —— 后台用，含隐藏的，不走缓存
 *   · create / update / delete 挂在 /admin/project/** 上（类级 @PreAuthorize("hasRole('ADMIN')")）
 *   四个内容模块（友链 / 项目 / 收藏 / 关于）刻意用同一套形状 ——
 *   同一件事只学一次，之后每加一个模块都只是"换个表名和字段"。
 */
public interface ProjectService {

    /**
     * 前台项目列表：只返回 status = 1（显示）的，按 sort 升序、id 升序。
     * 走 Redis 缓存（key 里带内容缓存版本号，任何写操作都会让它失效）。
     */
    List<ProjectVO> listVisible();

    /** 后台项目列表：含隐藏的那些，按 sort 升序、id 升序；不走缓存 */
    List<ProjectVO> listAll();

    /** 新建项目，返回新项目 id */
    Long create(ProjectForm form);

    /** 编辑项目（名称 / 简介 / 在线地址 / 仓库 / 封面 / 技术栈 / 排序 / 显示状态） */
    void update(Long id, ProjectForm form);

    /** 删除项目（逻辑删除） */
    void delete(Long id);
}
