package com.yigalaxy.yiguixingtu.setting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.setting.entity.SiteSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站点设置 Mapper。
 *
 * 【一行手写 SQL 都不需要，与 AboutMapper 同一个道理】
 *   这张表没有列表、没有筛选、没有分页，所有读都是"把 id = 1 那一行取出来"，
 *   BaseMapper 的 selectById / insert 够用；更新走的是 Service 里的
 *   LambdaUpdateWrapper（因为要能"把某个字段改回 NULL"，见 SettingServiceImpl）。
 *
 * 【为什么不写一个 selectForUpdate 之类的方法】
 *   设置是"管理员手工改的低频数据"，不存在需要行锁保护的并发写路径 ——
 *   两个管理员同时保存时，后者覆盖前者是可接受的语义（整份覆盖式提交）。
 *   加锁只会让代码看起来更严谨，实际挡住的问题一个也不存在。
 */
@Mapper
public interface SiteSettingMapper extends BaseMapper<SiteSetting> {
}
