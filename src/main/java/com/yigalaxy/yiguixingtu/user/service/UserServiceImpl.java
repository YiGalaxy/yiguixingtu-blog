package com.yigalaxy.yiguixingtu.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.user.dto.UserQuery;
import com.yigalaxy.yiguixingtu.user.dto.UserVO;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
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
        userMapper.insert(user);
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
                // and(...) 把"用户名 或 昵称"这组条件用小括号包起来，
                // 否则会和前面的 eq 条件混在一起，导致 SQL 逻辑错误
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(User::getUsername, query.getKeyword())
                        .or()
                        .like(User::getNickname, query.getKeyword()))
                .orderByDesc(User::getCreateTime);

        // ---- 执行分页查询 ----
        IPage<User> userPage = userMapper.selectPage(page, wrapper);

        // ---- 实体转 VO，绝不把 password 带出去 ----
        // convert(...) 会保留分页信息（总条数、总页数），只替换里面的数据
        return userPage.convert(this::toVO);
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

        log.info("修改用户角色: id={}, role={}", id, role);
    }



}
