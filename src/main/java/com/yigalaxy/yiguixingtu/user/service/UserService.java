package com.yigalaxy.yiguixingtu.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.user.dto.UserQuery;
import com.yigalaxy.yiguixingtu.user.dto.UserVO;
import com.yigalaxy.yiguixingtu.user.entity.User;

public interface UserService {

    /**
     * 用户注册
     * @param username
     * @param password
     * @param nickname
     * @return
     */
    User register(String username, String password, String nickname);

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
}
