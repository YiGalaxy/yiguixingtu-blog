package com.yigalaxy.yiguixingtu.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * =====================================================================
 * 管理员初始化引导 —— 解决"全新数据库里没有管理员，后台根本进不去"
 *
 * ============ 它解决的是什么问题 ============
 *
 * 这个项目有一个"鸡生蛋"的缺口（在写演示数据文档时发现的）：
 *   · 注册接口只会创建 GUEST（UserServiceImpl 里写死 setRole("GUEST")）
 *   · 而把用户提升为 ADMIN 的接口 PUT /user/{id}/role，【本身要求调用者是 ADMIN】
 * 于是全新部署的库里有 0 个管理员，也就没有任何办法造出一个管理员 ——
 * 后台永远进不去。本地一直没暴露，只是因为开发库里早就有手工建的管理员。
 *
 * 这个类在【应用启动时】补上这一步：
 *   库里有管理员 → 什么都不做
 *   库里没有管理员 + 配了引导账号 → 按配置建出/提升出第一个管理员
 *
 * ============ 用到的东西 ============
 *   · {@link ApplicationRunner} —— Spring Boot 提供的启动钩子：
 *     容器把全部 Bean 装配完成之后会调用它的 run()。
 *     选它而不是 @PostConstruct，是因为 @PostConstruct 执行时
 *     MyBatis 的 Mapper 可能还没准备好；ApplicationRunner 一定在"应用可以干活了"
 *     之后才跑，也【一定在 Flyway 建完表之后】——顺序天然是对的。
 *   · {@code @Value} 读配置 —— 引导账号的账号密码走环境变量注入，
 *     绝不写死在仓库里（理由同 application-prod.properties 里的注释）
 *   · {@link PasswordEncoder} —— 存进去的必须是 BCrypt 哈希。
 *     这一点很关键：如果图省事存明文，这个账号将来是无法通过登录校验的
 *     （登录时是拿 BCrypt.matches 比的），而且会在库里留一条明文密码。
 *   · {@code LambdaQueryWrapper} —— MyBatis-Plus 的类型安全条件构造器，
 *     用它 count/select 而不是手写 SQL 字符串
 *
 * ============ 代码构造为什么是这样 ============
 *   run() 只做一件事：把配置读出来的三个值交给
 *   {@link #bootstrapAdminIfAbsent}，然后负责打日志。
 *   真正判断逻辑全在 bootstrapAdminIfAbsent 里，而且它是 public 的 ——
 *   这样测试可以直接调它、并传入任意账号密码，
 *   不需要为了测"配置为空"再去起一个改了配置的 Spring 上下文。
 *
 * =====================================================================
 */
@Slf4j
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    /** 管理员角色值（与 UserServiceImpl 里用的字面量保持一致） */
    private static final String ROLE_ADMIN = "ADMIN";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /** 引导管理员账号；留空表示不启用引导 */
    @Value("${app.bootstrap-admin.username:}")
    private String bootstrapUsername;

    /** 引导管理员密码；留空表示不启用引导 */
    @Value("${app.bootstrap-admin.password:}")
    private String bootstrapPassword;

    /** 引导管理员的昵称，默认叫"站长" */
    @Value("${app.bootstrap-admin.nickname:站长}")
    private String bootstrapNickname;

    public AdminBootstrapRunner(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 应用启动完成后执行。
     *
     * 【为什么这里的失败【不能】让应用起不来】
     *   引导管理员只是"方便部署"的功能，不是业务主链路。
     *   如果它抛异常导致整个服务启动失败，那等于一个小功能的配置写错
     *   就把整个站点弄挂了 —— 代价完全不成比例。
     *   所以这里捕获异常、打错误日志，让应用照常启动，
     *   运维看到日志后可以手工处理（README 里给了引导 SQL）。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            bootstrapAdminIfAbsent(bootstrapUsername, bootstrapPassword, bootstrapNickname);
        } catch (Exception e) {
            log.error("管理员初始化引导失败，应用会继续启动。"
                    + "如果确实需要管理员，请参考 README「怎么得到第一个管理员账号」手工处理", e);
        }
    }

    /**
     * 若库里没有任何启用的管理员，则按传入的账号密码创建或提升出一个。
     *
     * 【几种情形分别怎么处理】
     *   | 库里已有管理员 | 配置为空 | 结果 |
     *   |---|---|---|
     *   | 有 | —— | 什么都不做（不是每次启动都去建账号） |
     *   | 没有 | 是 | 什么都不做，但打一条【警告】，提示后台将无法登录 |
     *   | 没有 | 否，且该用户名不存在 | 新建一个 ADMIN |
     *   | 没有 | 否，且该用户名已存在但角色是 GUEST | 把它提升为 ADMIN，**不改它的密码** |
     *   | 没有 | 否，且该用户名已经是 ADMIN | 视同"已有管理员"，什么都不做 |
     *
     * 【为什么"提升已有账号"时不改密码】
     *   改密码是个破坏性动作：万一运维把 BOOTSTRAP_ADMIN_USERNAME 填成了
     *   某个正在使用的账号，重置密码会把那个人挡在门外。
     *   而提升角色不破坏任何已有凭据，所以只做提升、不动密码，
     *   并在日志里明确写出来"密码未改动"。
     *
     * @param username 引导账号名（为空则不启用引导）
     * @param rawPassword 引导账号密码（为空则不启用引导）
     * @param nickname 昵称（为空则用默认值）
     * @return 处理结果的文字描述（便于日志与测试断言）
     */
    public String bootstrapAdminIfAbsent(String username, String rawPassword, String nickname) {

        // ---- 情形一：已经有管理员了，什么都不做 ----
        if (countAdminUsers() > 0) {
            log.info("已存在管理员账号，跳过管理员初始化引导");
            return "SKIPPED_ADMIN_EXISTS";
        }

        // ---- 情形二：没有管理员，但也没配引导账号 ----
        // 这里【故意打 warn 而不是 info】：这是一个需要人注意的状态 ——
        // 库是空的，说明这是全新部署，而后台将无法登录
        if (!StringUtils.hasText(username) || !StringUtils.hasText(rawPassword)) {
            log.warn("当前数据库没有任何管理员，且未配置 app.bootstrap-admin.username/password。"
                    + "后台将无法登录 —— 请设置环境变量 "
                    + "BOOTSTRAP_ADMIN_USERNAME / BOOTSTRAP_ADMIN_PASSWORD 后重启，"
                    + "或参考 README 用 SQL 手工提升一个管理员");
            return "SKIPPED_NOT_CONFIGURED";
        }

        String name = username.trim();
        User existing = findByUsername(name);

        // ---- 情形四：用户名已存在（说明之前注册过这个人）→ 只提升角色，不动密码 ----
        if (existing != null) {
            userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>()
                    .eq(User::getId, existing.getId())
                    .set(User::getRole, ROLE_ADMIN)
                    // 被禁用的账号提升成管理员也没用（登录会被拒），顺手启用
                    .set(User::getStatus, 1));
            log.info("已把已有账号 [{}] 提升为管理员（密码未改动，仍用它原来的密码登录）", name);
            return "PROMOTED";
        }

        // ---- 情形三：新建一个管理员 ----
        User admin = new User();
        admin.setUsername(name);
        // 必须存 BCrypt 哈希：登录时是用 BCrypt.matches 校验的，
        // 存明文的话这个账号根本登不进去（而且等于在库里留了明文密码）
        admin.setPassword(passwordEncoder.encode(rawPassword));
        admin.setNickname(StringUtils.hasText(nickname) ? nickname.trim() : "站长");
        admin.setRole(ROLE_ADMIN);
        admin.setStatus(1);
        admin.setDeleted(0);
        userMapper.insert(admin);

        // 【刻意不打日志输出密码】
        // 日志会被收集、转发、长期保存，密码写进日志等于泄漏 —— 所以只说"建了谁"
        log.info("已创建初始管理员账号 [{}]，请登录后立即修改密码", name);
        return "CREATED";
    }

    /** 统计【未删除且启用】的 ADMIN 数量：被禁用的管理员不算数（他也登不进去） */
    private long countAdminUsers() {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getRole, ROLE_ADMIN)
                .eq(User::getStatus, 1));
    }

    /** 按用户名查一个未删除的用户（@TableLogic 会自动加上 deleted = 0） */
    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
    }
}
