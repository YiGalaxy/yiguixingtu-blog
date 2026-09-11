package com.yigalaxy.yiguixingtu.about.service;

import com.yigalaxy.yiguixingtu.about.dto.AboutForm;
import com.yigalaxy.yiguixingtu.about.dto.AboutVO;

/**
 * 关于页服务。
 *
 * 【只有两个方法，因为这份数据只有一份】
 *   · {@link #get()} —— 公开只读（SecurityConfig 放行了 GET /about），走 Redis 缓存
 *   · {@link #update(AboutForm)} —— 后台保存（类级 ADMIN），保存后让缓存失效
 *   没有 create / delete：那一行是迁移脚本插进去的，而且"删掉关于页"这个需求不存在
 *   （不要了就清空字段）。
 */
public interface AboutService {

    /**
     * 取关于页信息。
     *
     * 【永远不返回 null】这一行正常情况下由迁移脚本插好；
     * 万一被手工删掉，这里会返回一个 id 与 nickname 都给默认值的对象
     * （页面照常打开、只是内容空着），而不是让前台的公开页面 404 或 500。
     * 理由与兜底方式见实现类的注释。
     */
    AboutVO get();

    /** 保存关于页信息（单条更新；那一行缺失时会自愈地写回来） */
    void update(AboutForm form);
}
