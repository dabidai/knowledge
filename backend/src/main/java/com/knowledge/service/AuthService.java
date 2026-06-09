package com.knowledge.service;

import com.knowledge.dto.LoginRequest;
import com.knowledge.dto.LoginResponse;
import com.knowledge.entity.User;
import com.knowledge.repository.UserRepository;
import com.knowledge.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 认证服务 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(
                user.getUsername(), user.getRole(), user.getDepartment().getName()
        );

        log.info("用户 {} 登录成功", user.getUsername());
        return new LoginResponse(token, user.getUsername(), user.getRole(),
                user.getDepartment().getName());
    }
}
