package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.controller.AuthController;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BackendSecurityContractTest {

    @Test
    @DisplayName("生产 profile 必须用显式 JWT_SECRET 覆盖开发密钥")
    void productionProfileRequiresJwtSecret() throws IOException {
        Properties prod = loadProperties("application-prod.properties");

        assertEquals("${JWT_SECRET}", prod.getProperty("jwt.secret"));
    }

    @Test
    @DisplayName("匿名注册使用独立的应用层限流配额")
    void registrationHasDedicatedRateLimiter() throws ReflectiveOperationException, IOException {
        RateLimiter limiter = AuthController.class
                .getMethod("register", com.yigalaxy.yiguixingtu.auth.dto.RegisterRequest.class)
                .getAnnotation(RateLimiter.class);
        assertNotNull(limiter);
        assertEquals("registerRateLimiter", limiter.name());

        Properties common = loadProperties("application.properties");
        assertEquals("10", common.getProperty(
                "resilience4j.ratelimiter.instances.registerRateLimiter.limit-for-period"));
        assertEquals("1m", common.getProperty(
                "resilience4j.ratelimiter.instances.registerRateLimiter.limit-refresh-period"));
        assertEquals("0", common.getProperty(
                "resilience4j.ratelimiter.instances.registerRateLimiter.timeout-duration"));
    }

    private Properties loadProperties(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("classpath 上找不到 " + name);
            }
            properties.load(input);
        }
        return properties;
    }
}
