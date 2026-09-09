package com.yigalaxy.yiguixingtu.auth;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性，读取 application.properties 里 jwt.* 的配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    /** 签名密钥（至少32字节） */
    private String secret;

    /** 过期时间（毫秒） */
    private long expireTime;

}
