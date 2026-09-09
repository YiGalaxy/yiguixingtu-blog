package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
class YiguixingtuApplicationTests {

    @Autowired
    private UserMapper userMapper;


    @Test
    void contextLoads() {
        User user = userMapper.selectById(1L);
        log.info("查询结果:{}", user);
    }

}
