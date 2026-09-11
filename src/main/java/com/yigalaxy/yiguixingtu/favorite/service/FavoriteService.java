package com.yigalaxy.yiguixingtu.favorite.service;

import com.yigalaxy.yiguixingtu.favorite.dto.FavoriteForm;
import com.yigalaxy.yiguixingtu.favorite.dto.FavoriteVO;

import java.util.List;

/**
 * 收藏服务。
 *
 * 【与友链 / 项目完全同形】公开只读 listVisible（走缓存、只含 status = 1）
 * + 后台 listAll（含隐藏、不走缓存）+ create / update / delete（类级 ADMIN）。
 *
 * 【为什么不提供"按分组查"的接口】
 *   收藏总量几十条，一次全拉回去前端 groupBy 是最省事的做法：
 *   多一个 ?category= 参数就多一组"分组名要精确匹配还是模糊匹配 / 要不要 URL 编码"
 *   的问题，而收益只是少传几 KB。
 */
public interface FavoriteService {

    /** 前台收藏列表：只返回 status = 1，按 sort 升序、id 升序；走 Redis 缓存 */
    List<FavoriteVO> listVisible();

    /** 后台收藏列表：含隐藏的，按 sort 升序、id 升序；不走缓存 */
    List<FavoriteVO> listAll();

    /** 新建收藏，返回新收藏 id */
    Long create(FavoriteForm form);

    /** 编辑收藏（标题 / 地址 / 备注 / 分组 / 排序 / 显示状态） */
    void update(Long id, FavoriteForm form);

    /** 删除收藏（逻辑删除） */
    void delete(Long id);
}
