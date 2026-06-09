package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.LoginRequest;
import com.knowledge.dto.LoginResponse;
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

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse resp = authService.login(request);
        return ApiResponse.ok(resp);
    }
}
