package com.yigalaxy.yiguixingtu;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yigalaxy.yiguixingtu.config.AdminBootstrapRunner;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapRunnerSecurityTest {

    @Test
    @DisplayName("已有公开注册账号的密码不匹配时，引导流程拒绝提权")
    void existingAccountWithDifferentPassword_shouldNotBePromoted() {
        UserMapper userMapper = mock(UserMapper.class);
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        User existing = new User();
        existing.setId(42L);
        existing.setUsername("pre_registered");
        existing.setPassword(passwordEncoder.encode("attacker-chosen-password"));
        existing.setRole("GUEST");
        existing.setStatus(1);

        when(userMapper.selectCount(org.mockito.ArgumentMatchers.<Wrapper<User>>any())).thenReturn(0L);
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<User>>any())).thenReturn(existing);

        AdminBootstrapRunner runner = new AdminBootstrapRunner(userMapper, passwordEncoder);

        String result = runner.bootstrapAdminIfAbsent("pre_registered", "operator-secret", "站长");

        assertEquals("SKIPPED_USERNAME_TAKEN", result);
        verify(userMapper, never()).update(org.mockito.ArgumentMatchers.<User>isNull(),
                org.mockito.ArgumentMatchers.<Wrapper<User>>any());
    }
}
