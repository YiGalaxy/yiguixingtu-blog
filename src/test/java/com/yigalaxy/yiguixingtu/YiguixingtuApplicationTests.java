package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

@Slf4j
@SpringBootTest
class YiguixingtuApplicationTests {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JwtUtil jwtUtil;


    @Test
    void contextLoads() {
        User user = userMapper.selectById(1L);
        log.info("查询结果:{}", user);
    }
    @Test
    void testGenerateAndParse() {
        // 生成token
        String token = jwtUtil.generateToken(1L, "yigalaxy", "ADMIN");
        log.info("生成的token: {}", token);

        // 解析token
        Claims claims = jwtUtil.parseToken(token);
        log.info("解析结果: subject={}, userId={}, role={}",
                claims.getSubject(), claims.get("userId"), claims.get("role"));

        // 校验
        log.info("===== 校验通过: {}", jwtUtil.isValid(token));
    }

    @Autowired
    private UserDetailsService userDetailsService;

    @Test
    void testLoadUserByUsername() {
        // 前提：user 表里有 username='yigalaxy' 这条数据
        UserDetails ud = userDetailsService.loadUserByUsername("yigalaxy");
        log.info("加载到的用户: username={}, authorities={}, enabled={}",
                ud.getUsername(), ud.getAuthorities(), ud.isEnabled());
    }

}
