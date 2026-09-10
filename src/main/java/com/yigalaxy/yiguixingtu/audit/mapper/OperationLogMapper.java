package com.yigalaxy.yiguixingtu.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.audit.OperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计 Mapper。
 *
 * 【为什么这个类单独放在 mapper 子包里，而不是和其它审计类并排】
 *   项目统一的 Mapper 扫描规则是 {@code @MapperScan("com.yigalaxy.yiguixingtu.**.mapper")}
 *   （见 {@link com.yigalaxy.yiguixingtu.config.MybatisPlusConfig}），
 *   这条规则里的 {@code **.mapper} 会在类路径上拼成
 *   {@code .../**&#47;mapper/**&#47;*.class} 这样的 Ant 路径 ——
 *   **只有放在名为 mapper 的目录下的接口才会被扫到**。
 *
 *   ⚠️ 这不是"风格问题"，我第一次就是把它平放在 audit 包下的，后果很具体：
 *   编译通过、启动直接失败 ——
 *   {@code No qualifying bean of type 'OperationLogMapper' available}。
 *   Mapper 没有代理实现，监听器就注不进来，整个应用起不来。
 *   （所以宁可多一层包，也不去把扫描规则改宽 —— 改宽会连带扫到
 *     其它包下所有接口，风险比多一个目录大得多。）
 *
 * 【为什么直接用 BaseMapper 而不写任何自定义方法】
 *   审计只做两件事：插入一条、按条件查（查询接口还没做）。
 *   MyBatis-Plus 的 BaseMapper 已经提供了 insert / selectPage，
 *   一条自定义 SQL 都不需要 —— "能用现成的就不自己写"，
 *   这一条在数据访问层同样适用。
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
