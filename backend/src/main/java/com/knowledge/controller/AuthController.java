package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.LoginRequest;
import com.knowledge.dto.LoginResponse;
import com.knowledge.dto.UserResponse;
import com.knowledge.entity.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.knowledge.security.RateLimit;
import com.knowledge.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 认证控制器 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal User user) {
        return ApiResponse.ok(UserResponse.from(user));
    }

    @PostMapping("/login")
    @RateLimit(maxRequests = 5, windowSeconds = 60)
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse resp = authService.login(request);
        return ApiResponse.ok(resp);
    }
}
