package com.knowledge.security;

import com.knowledge.entity.User;
import com.knowledge.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** JWT 认证过滤器 —— 在每个请求中提取并验证 JWT */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);

        Long userId = null;
        if (token != null) {
            try {
                userId = jwtUtil.parseUserId(token);
            } catch (io.jsonwebtoken.JwtException | IllegalArgumentException ignored) {
                // 不建立身份；受保护接口由 SecurityFilterChain 统一返回 401。
            }
        }
        if (userId != null) {
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isPresent() && Boolean.TRUE.equals(userOpt.get().getEnabled())
                    && ("admin".equals(userOpt.get().getRole()) || "default".equals(userOpt.get().getRole()))) {
                User user = userOpt.get();
                List<SimpleGrantedAuthority> authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase())
                );

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        chain.doFilter(request, response);
    }

    /** 从 Authorization 头中提取 Bearer Token */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
