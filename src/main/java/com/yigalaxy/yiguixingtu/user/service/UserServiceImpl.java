package com.yigalaxy.yiguixingtu.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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
}
