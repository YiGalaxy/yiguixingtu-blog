package com.yigalaxy.yiguixingtu.auth.util;

import com.yigalaxy.yiguixingtu.auth.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类：负责生成和解析 token
 *
 * 先理解 JWT 是什么：
 * JWT（JSON Web Token）就是一个"加密的字符串"，由3段用 . 隔开组成：
 *     header.payload.signature
 * 1. header    : 标明用哪种加密算法（这里是 HS256）
 * 2. payload   : 真正存的数据（对你来说就是 userId、role、username、过期时间）
 * 3. signature : 用"密钥"把前两段加密算出来的签名，用来防止被篡改
 *
 * 只要别人的密钥不知道，就伪造不出一个能通过校验的 token。
 */
@Slf4j
@Component
public class JwtUtil {

    /** JWT 的配置（secret 密钥、过期时间），由 Spring 注入进来 */
    private final JwtProperties jwtProperties;

    /** 签名用的密钥对象，构造时根据配置里的 secret 生成一次，之后复用 */
    private final SecretKey secretKey;

    /**
     * 构造方法：Spring 在创建这个 Bean 时自动调用，
     * 把 JwtProperties 传进来，并提前把"签名密钥"算好。
     */
    public JwtUtil(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;

        // Keys.hmacShaKeyFor：把"一串字符串密钥"转换成 HMAC 算法能用的密钥对象
        // HS256 要求密钥至少 32 字节（256位），太短会报错
        // getBytes(UTF_8) 把字符串转成字节数组
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 token（签发）
     *
     * @param userId   用户ID
     * @param username 用户名
     * @param role     角色（ADMIN/GUEST）
     * @return 生成的 JWT 字符串
     */
    public String generateToken(Long userId, String username, String role) {
        // 1. 准备要放进 token 里的"自定义数据"（也就是 payload 里的 claims）
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);   // 存用户ID
        claims.put("role", role);       // 存角色

        // 2. 记录当前时间（token 的签发时间）
        Date now = new Date();
        // 3. 计算过期时间 = 现在 + 配置里的过期时长（毫秒）
        Date expiration = new Date(now.getTime() + jwtProperties.getExpireTime());

        // 4. 开始组装 token
        String token = Jwts.builder()
                .claims(claims)          // 把自定义数据（userId、role）放进去
                .subject(username)       // 把用户名作为"主题"放进去（标准字段）
                .issuedAt(now)           // 签发时间
                .expiration(expiration)  // 过期时间
                .signWith(secretKey)     // 用密钥签名（最后一段 signature）
                .compact();              // 组装成最终的 JWT 字符串

        // 5. 打日志
        log.info("生成token成功, userId={}, username={}, role={}", userId, username, role);
        return token;
    }

    /**
     * 解析 token（校验 + 取数据）
     * 注意：如果 token 无效（签名不对、已过期、格式错误），这里会直接抛异常。
     *
     * @param token JWT 字符串
     * @return 解析出来的数据（Claims，就是 payload 里的内容）
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)   // 用同一个密钥去校验签名是否匹配
                .build()                 // 构建解析器
                .parseSignedClaims(token)// 解析签名过的 Claims
                .getPayload();           // 取出 payload（userId、role、username 等）
    }

    /**
     * 校验 token 是否有效（不抛异常版本）
     * 适合放在过滤器里：能解析成功就返回 true，否则返回 false
     *
     * @param token JWT 字符串
     * @return true=有效，false=无效/过期
     */
    public boolean isValid(String token) {
        try {
            parseToken(token);   // 如果能解析成功，说明签名正确且没过期
            return true;
        } catch (Exception e) {
            log.warn("token校验失败: {}", e.getMessage());
            return false;
        }
    }
}