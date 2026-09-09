package com.yigalaxy.yiguixingtu.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.user.entity.User;
import org.apache.ibatis.annotations.Mapper;


/**
 * 用户 Mapper。继承 BaseMapper 后，增删改查方法都是现成的。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
