package com.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 登录请求 */
@Data
public class LoginRequest {
    @NotBlank
    @Size(max = 50)
    private String username;
    @NotBlank
    @Size(max = 72)
    private String password;
}
