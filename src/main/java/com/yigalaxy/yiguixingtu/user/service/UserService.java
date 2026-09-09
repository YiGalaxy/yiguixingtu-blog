package com.yigalaxy.yiguixingtu.user.service;

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
}
