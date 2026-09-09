package com.yigalaxy.yiguixingtu.auth;

import com.yigalaxy.yiguixingtu.user.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

/**
 * Spring Security 认识的用户对象。
 * 把我们的 User 实体包装起来，实现 Spring Security 的 UserDetails 接口，
 * 这样它才能用于登录认证、角色判断。
 */
public class LoginUser implements UserDetails {

    /** 原始的用户实体 */
    private final User user;

    public LoginUser(User user) {
        this.user = user;
    }

    /** 供外部取原始用户（比如拿 userId、nickname） */
    public User getUser() {
        return user;
    }

    /**
     * 返回用户的权限（角色）。
     * 约定：角色前面加 ROLE_ 前缀，Spring Security 的 hasRole('ADMIN') 会去找 ROLE_ADMIN
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (user.getRole() == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
    }

    /** 密码（BCrypt哈希），登录时 Spring Security 会拿它和用户输入的密码比对 */
    @Override
    public String getPassword() {
        return user.getPassword();
    }

    /** 用户名 */
    @Override
    public String getUsername() {
        return user.getUsername();
    }

    // 下面几个"账号状态"判断，先都返回 true；真正用到的是 isEnabled（账号是否启用）

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * 账号是否启用：对应我们 user 表的 status 字段（1=启用, 0=禁用）
     */
    @Override
    public boolean isEnabled() {
        return user.getStatus() != null && user.getStatus() == 1;
    }
}