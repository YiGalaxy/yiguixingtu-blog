package com.yigalaxy.yiguixingtu.setting.service;

import com.yigalaxy.yiguixingtu.setting.dto.SettingForm;
import com.yigalaxy.yiguixingtu.setting.dto.SettingVO;

/**
 * 站点设置服务。
 *
 * 【只有两个方法，与 AboutService 同形】
 *   · {@link #get()} —— 公开只读（SecurityConfig 放行了 GET /setting），走 Redis 缓存
 *   · {@link #update(SettingForm)} —— 后台保存（类级 ADMIN），保存后让缓存失效
 *   没有 create / delete：那一行是迁移脚本插进去的，"新建一份站点设置"没有语义，
 *   不要了就把字段改回默认值。
 *
 * 【为什么读写共用一个 VO 而不是拆两个接口】
 *   见 SettingVO 的类注释（一句话：前后台要的字段完全一样）。
 */
public interface SettingService {

    /**
     * 取站点设置。
     *
     * 【永远不返回 null、也不抛 404】那一行正常情况下由 V12 迁移脚本插好。
     * 万一被手工删掉（或者将来某次迁移出了岔子），这里会返回一份【内置默认值】：
     *   · 评论开关给 `true` —— 它是行为类字段，为 null 会让前端不知道该不该
     *     渲染评论框（两边猜的方向还可能相反），那是把"配置缺失"升级成"功能损坏"
     *   · 其余字段（站点名 / 公告 / 页脚三行 / 每页条数）留 null ——
     *     前端对 null 的语义就是"这一块不渲染 / 用自己的默认值"，
     *     正好也是这时候该有的表现。⚠️ 每页条数的默认值属于前端的排版决策，
     *     后端不再写一份（理由见实现类的注释）
     * 同时打一条 WARN，让运维知道"设置那一行不见了"；在后台点一次保存即可写回。
     * 理由与 AboutServiceImpl 里那段完全一致：公开页面的可用性优先于
     * "把数据缺失变成显式错误"。
     */
    SettingVO get();

    /** 保存站点设置（单条更新；那一行缺失时会自愈地写回来） */
    void update(SettingForm form);
}
