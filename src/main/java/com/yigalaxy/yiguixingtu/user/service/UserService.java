package com.yigalaxy.yiguixingtu.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.user.dto.UserQuery;
import com.yigalaxy.yiguixingtu.user.dto.UserVO;
import com.yigalaxy.yiguixingtu.user.entity.User;

public interface UserService {

    /**
     * 用户注册。
     *
     * @param username 登录账号（唯一）
     * @param password 明文密码，内部 BCrypt 加密后存储
     * @param nickname 昵称（选填）
     * @param email    邮箱（**必填**，格式由 RegisterRequest 校验；见该类的说明）
     * @return 落库后的用户实体（不含明文密码，password 字段是哈希）
     */
    User register(String username, String password, String nickname, String email);

    /**
     * 根据账号来获取用户
     * @param username
     * @return
     */
    User getUserByUsername(String username);

    /**
     * 根据账号ID来获取用户
     * @param id
     * @return
     */
    User getUserById(Long id);

    /**
     * 用户分页查询（后台管理用）
     * @param query 分页 + 筛选条件
     * @return 分页结果（VO，不含密码）
     */
    IPage<UserVO> pageUsers(UserQuery query);

    /**
     * 启用 / 禁用用户
     * @param id     用户ID
     * @param status 1=启用 0=禁用
     */
    void updateStatus(Long id, Integer status);

    /**
     * 修改用户角色
     * @param id   用户ID
     * @param role ADMIN / GUEST
     */
    void updateRole(Long id, String role);

    /**
     * 删除用户（逻辑删除）
     * @param id 用户ID
     */
    void removeUser(Long id);

    /**
     * 重置用户密码
     * @param id       用户ID
     * @param password 新密码（明文传入，内部会用 BCrypt 加密后存储）
     */
    void resetPassword(Long id, String password);
}
