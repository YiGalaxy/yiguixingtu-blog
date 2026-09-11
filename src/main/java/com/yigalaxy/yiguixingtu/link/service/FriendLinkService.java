package com.yigalaxy.yiguixingtu.link.service;

import com.yigalaxy.yiguixingtu.link.dto.FriendLinkForm;
import com.yigalaxy.yiguixingtu.link.dto.FriendLinkVO;

import java.util.List;

/**
 * 友链服务。
 *
 * 【读接口分两个，写接口只有管理员能用】
 *   · {@link #listVisible()} —— 前台公开（SecurityConfig 放行了 GET /link/list），
 *     只返回 status = 1 的那些，走 Redis 缓存
 *   · {@link #listAll()} —— 后台管理用，含隐藏的，【不走缓存】
 *   · create / update / delete 挂在 /admin/link/** 上（类级 @PreAuthorize）
 *
 * 【为什么"前台只读"这个方法名里有 Visible、后台那个是 All】
 *   名字里直接体现"返回的范围不一样"是最省事的做法 ——
 *   比两个都叫 list() 然后靠参数区分要好：
 *   调用方在 IDE 里补全时就能看出哪个是给前台用的。
 */
public interface FriendLinkService {

    /**
     * 前台友链列表：只返回 status = 1（显示）的，按 sort 升序、id 升序。
     * 走 Redis 缓存（key 里带内容缓存版本号，任何写操作都会让它失效）。
     */
    List<FriendLinkVO> listVisible();

    /**
     * 后台友链列表：含隐藏的那些，按 sort 升序、id 升序。
     *
     * 【为什么它不走缓存】管理员刚点完"隐藏"就应当立刻看到效果；
     * 走缓存只会带来"是不是没保存成功"的疑惑。
     * 后台的调用频率是"一个人一天几次"，缓存它没有任何收益。
     * （和 AdminTagController 的 list 是同一个决定，分类那边则因为读写共用一个带缓存的方法。）
     */
    List<FriendLinkVO> listAll();

    /** 新建友链，返回新友链 id */
    Long create(FriendLinkForm form);

    /** 编辑友链（改名 / 改地址 / 改头像 / 改简介 / 改排序 / 改状态） */
    void update(Long id, FriendLinkForm form);

    /** 删除友链（逻辑删除，删错了还能在数据库里恢复） */
    void delete(Long id);
}
