package com.knowledge.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** JWT 令牌工具类 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expiration;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration}") long expiration) {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("请配置至少 32 字节的随机 JWT_SECRET");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        if (expiration <= 0) throw new IllegalArgumentException("jwt.expiration 必须大于 0");
        this.expiration = expiration;
    }

    /** 生成 JWT Token */
    public String generateToken(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("用户 ID 无效");
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    /** 同一次解析中校验签名、有效期与身份；不接受用户名格式。 */
    public Long parseUserId(String token) {
        Claims claims = parseClaims(token);
        String subject = claims.getSubject();
        if (claims.getExpiration() == null || subject == null || !subject.matches("[1-9][0-9]*"))
            throw new IllegalArgumentException("Token 身份或有效期无效");
        return Long.valueOf(subject);
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
