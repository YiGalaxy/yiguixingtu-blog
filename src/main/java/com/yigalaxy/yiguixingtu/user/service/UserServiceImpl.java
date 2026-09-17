package com.yigalaxy.yiguixingtu.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigalaxy.yiguixingtu.auth.cache.UserAuthCache;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.user.dto.UserQuery;
import com.yigalaxy.yiguixingtu.user.dto.UserVO;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    /** 用户认证信息缓存：任何会改变"用户是否合法 / 角色"的操作，都必须清掉它 */
    private final UserAuthCache userAuthCache;

    /**
     * 操作审计记录器。
     * 用户管理全是"改权限、改状态、删账号"这类高危动作，
     * 出事之后第一个要回答的问题就是"谁在什么时候干的"，所以每个写方法都要记一笔。
     * 具体怎么落库见 audit 包的 OperationLogRecorder / OperationLogListener。
     */
    private final OperationLogRecorder operationLogRecorder;

    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           UserAuthCache userAuthCache,
                           OperationLogRecorder operationLogRecorder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.userAuthCache = userAuthCache;
        this.operationLogRecorder = operationLogRecorder;

    }

    /**
     * 用户注册
     * @param username
     * @param password
     * @param nickname
     * @return
     */
    @Override
    public User register(String username, String password, String nickname) {
        //1.先查重
        if(getUserByUsername(username) != null){
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }
        //2.开始封装信息为User类型
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setRole("GUEST");
        user.setStatus(1);
        user.setDeleted(0);
        //3.开始插入
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发下的兜底：两个请求同时通过上面的查重、一起 INSERT，
            // 其中一个必然撞 uk_username。翻译成业务错误，而不是让用户看到 500。
            // （和 TagServiceImpl.create / CategoryServiceImpl.create 是同一个处理。）
            //
            // 【为什么这条路径比别处更容易撞上】
            //   /auth/register 是 permitAll 的公开接口，不需要登录就能调用 ——
            //   用户连点两次提交、或者脚本扫，都能造出"同一个用户名同时进来两次"。
            //   上面的查重在两次请求之间没有任何互斥，挡不住这种情况。
            log.warn("注册时撞上用户名唯一索引（并发提交）: username={}", username);
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }
        //4.返回成功信息
        log.info("注册用户成功:{}",username);
        return user;
    }

    /**
     * 根据账号来获取用户
     * @param username
     * @return
     */
    @Override
    public User getUserByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername,username));
    }

    /**
     * 根据账号ID来获取用户
     * @param id
     * @return
     */
    @Override
    public User getUserById(Long id) {
        return userMapper.selectById(id);
    }

    /**
     * 分页查询用户
     * @param query 分页 + 筛选条件
     * @return
     */
    @Override
    public IPage<UserVO> pageUsers(UserQuery query) {
        // ---- 参数兜底：防止前端传 0、负数或超大 size ----
        long pageNo = (query.getPage() == null || query.getPage() < 1) ? 1L : query.getPage();
        long pageSize = (query.getSize() == null || query.getSize() < 1) ? 10L : query.getSize();
        if (pageSize > 100) {
            pageSize = 100;    // 上限保护：不允许一次拉太多，防止拖垮数据库
        }

        // ---- 构造分页对象：第几页、每页几条 ----
        // MyBatis-Plus 的分页插件会自动把 SQL 改写成 LIMIT，并额外查一次总数
        Page<User> page = new Page<>(pageNo, pageSize);

        // ---- 构造查询条件 ----
        // 每个条件第一个参数是"是否拼接该条件"，这样就能动态组装 SQL：
        //   role 为空     -> 不加 role 条件
        //   keyword 为空  -> 不加模糊查询条件
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(StringUtils.hasText(query.getRole()), User::getRole, query.getRole())
                .eq(query.getStatus() != null, User::getStatus, query.getStatus())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(User::getUsername, query.getKeyword())
                        .or()
                        .like(User::getNickname, query.getKeyword()));

        // ---- 排序：走白名单方法（见下），不让前端直接决定 SQL ----
        applySort(wrapper, query.getSortField(), query.getSortOrder());

        // ---- 执行分页查询 ----
        IPage<User> userPage = userMapper.selectPage(page, wrapper);

        // ---- 实体转 VO，绝不把 password 带出去 ----
        // convert(...) 会保留分页信息（总条数、总页数），只替换里面的数据
        return userPage.convert(this::toVO);
    }
    /**
     * 应用排序。
     *
     * 【为什么必须用白名单，不能直接把前端传的字段名拼进 SQL？】
     * 如果无脑拼成 "order by " + sortField，前端传
     *     id; DELETE FROM user
     * 这种内容就可能造成 SQL 注入。
     * 用 switch 做白名单后，前端只能在这几个字段里选，
     * 传了别的字段一律走默认排序 —— 从根上杜绝注入。
     *
     * @param wrapper   查询条件对象
     * @param sortField 前端传的排序字段（对应表格列的 prop）
     * @param sortOrder "asc" 升序 / "desc" 降序
     */
    private void applySort(LambdaQueryWrapper<User> wrapper, String sortField, String sortOrder) {
        boolean asc = "asc".equalsIgnoreCase(sortOrder);

        switch (sortField == null ? "" : sortField) {
            case "id" -> {
                if (asc) wrapper.orderByAsc(User::getId);
                else wrapper.orderByDesc(User::getId);
            }
            case "username" -> {
                if (asc) wrapper.orderByAsc(User::getUsername);
                else wrapper.orderByDesc(User::getUsername);
            }
            case "nickname" -> {
                if (asc) wrapper.orderByAsc(User::getNickname);
                else wrapper.orderByDesc(User::getNickname);
            }
            case "role" -> {
                if (asc) wrapper.orderByAsc(User::getRole);
                else wrapper.orderByDesc(User::getRole);
            }
            case "status" -> {
                if (asc) wrapper.orderByAsc(User::getStatus);
                else wrapper.orderByDesc(User::getStatus);
            }
            case "createTime" -> {
                if (asc) wrapper.orderByAsc(User::getCreateTime);
                else wrapper.orderByDesc(User::getCreateTime);
            }
            // 没传 或 传了非法字段 -> 默认按创建时间倒序
            default -> wrapper.orderByDesc(User::getCreateTime);
        }
    }



    /**
     * 实体 -> VO 的转换（抽成私有方法，方便复用）
     * @param user
     * @return
     */
    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

    /**
     * 启用 / 禁用用户
     * @param id     用户ID
     * @param status 1=启用 0=禁用
     */
    @Override
    public void updateStatus(Long id, Integer status) {

        // 1. 校验参数：status 只能是 0 或 1
        if (status == null || (status != 1 && status != 0)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态值只能是 1(正常) 或 0(禁用)");
        }

        // 2. 校验用户是否存在
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 3. 只更新 status 字段（用 updateById，MyBatis-Plus 只会更新非 null 字段）
        User update = new User();
        update.setId(id);
        update.setStatus(status);
        userMapper.updateById(update);

        // 4.【关键】清掉缓存。
        //    否则被禁用的人靠缓存里的 status=1 还能继续访问，
        //    最长撑到 TTL 到期（30 分钟）—— 那这个功能就形同虚设。
        userAuthCache.evict(id);

        // 记一笔审计：启用/禁用直接决定这个账号还能不能登录，属于权限类高危操作
        // detail 里带上用户名，因为审计表里只有 target_id，
        // 事后翻审计时能直接看懂"动的是谁"，不用再回查 user 表
        operationLogRecorder.record(OperationAction.UPDATE_USER_STATUS, AuditTarget.USER, id,
                "用户=" + user.getUsername() + "，" + (status == 1 ? "启用" : "禁用"));

        log.info("修改用户状态: id={}, status={}", id, status);
    }


    /**
     * 修改用户角色
     * @param id   用户ID
     * @param role ADMIN / GUEST
     */
    @Override
    public void updateRole(Long id, String role) {

        // 1. 校验角色：只允许这两个值
        if (!"ADMIN".equals(role) && !"GUEST".equals(role)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "角色只能是 ADMIN 或 GUEST");
        }

        // 2. 校验用户是否存在
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 3. 更新角色
        User update = new User();
        update.setId(id);
        update.setRole(role);
        userMapper.updateById(update);

        // 4.【关键】角色变了，但缓存里还是旧角色 —— 会导致越权
        //    （比如刚被降级的人，缓存里仍是 ADMIN，还能进后台）
        userAuthCache.evict(id);

        // 记一笔审计：改角色就是改权限（GUEST → ADMIN 等于提权），最需要留痕
        // detail 里把"从什么角色改成什么角色"都写下来：
        // 只记改完之后的值，事后根本看不出这是一次提权还是一次降权
        operationLogRecorder.record(OperationAction.UPDATE_USER_ROLE, AuditTarget.USER, id,
                "用户=" + user.getUsername() + "，角色 " + user.getRole() + " → " + role);

        log.info("修改用户角色: id={}, role={}", id, role);
    }

    /**
     * 删除用户（逻辑删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeUser(Long id) {

        // 1. 校验用户是否存在
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 2.【关键坑】逻辑删除后这一行仍然留在表里，而 username 上有唯一索引。
        //    如果不改写 username，以后有人用同一个用户名注册时：
        //      · 带 @TableLogic 的查重语句看不到已删除的行 -> 误判为"不重复"
        //      · 但 INSERT 时物理行还在 -> 直接撞唯一索引报 Duplicate entry
        //    所以删除时顺便把用户名改名：既保留历史数据，又释放用户名。
        User rename = new User();
        rename.setId(id);
        rename.setUsername(user.getUsername() + "#deleted#" + id);
        userMapper.updateById(rename);

        // 3. 逻辑删除：@TableLogic 会把它变成 UPDATE user SET deleted=1 WHERE id=?
        userMapper.deleteById(id);

        // 4.【关键】人删了，缓存还留着 = 幽灵账号，拿旧 token 依然能通行
        userAuthCache.evict(id);

        // 记一笔审计：删除是最不可逆的操作（这里只是逻辑删除，但账号等于废了），
        // detail 里留一份【改写之前】的用户名快照 ——
        // 上面刚把 username 改写成 "原名#deleted#id"，
        // 之后就再也无法从 user 表看出"删掉的到底是哪个账号"，只有这条记录能还原。
        // 另外本方法是 @Transactional 的，事件绑在事务上，回滚时不会留下这条记录。
        operationLogRecorder.record(OperationAction.DELETE_USER, AuditTarget.USER, id,
                "用户=" + user.getUsername());

        log.info("删除用户: id={}, username={}", id, user.getUsername());
    }


    /**
     * 重置用户密码
     */
    @Override
    public void resetPassword(Long id, String password) {

        // 1. 校验密码长度（DTO 上也校验了一次，这里再兜一层：Service 可能被别处调用）
        if (password == null || password.length() < 6 || password.length() > 20) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码长度需要在6-20之间");
        }

        // 2. 校验用户是否存在
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 3. BCrypt 加密后更新（绝不能存明文）
        User update = new User();
        update.setId(id);
        update.setPassword(passwordEncoder.encode(password));
        userMapper.updateById(update);

        // 4. 清缓存：缓存里虽然不含密码，但保持"一改用户就清缓存"的一致习惯；
        //    将来若要做"改密码后旧 token 立即失效"，这里就不用再补了
        userAuthCache.evict(id);

        // 记一笔审计：能回答"这个账号的密码是谁、什么时候重置的"，
        // 排查"用户说登不上"时这是第一手线索。
        // ⚠️ detail 里【绝不能】带上密码 —— 审计表是长期保留的，
        //    明文密码写进去等于把泄露面从"业务库"扩大到"审计库"。
        // 本方法没有 @Transactional，事件靠 fallbackExecution 兜底立即派发，
        // 所以别把 record 挪到 @Transactional 方法之外去，否则会丢掉"事务回滚就不记录"的保证。
        operationLogRecorder.record(OperationAction.RESET_USER_PASSWORD, AuditTarget.USER, id,
                "用户=" + user.getUsername());

        log.info("重置用户密码: id={}", id);
    }


}


