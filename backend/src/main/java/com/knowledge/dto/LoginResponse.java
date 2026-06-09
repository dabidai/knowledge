package com.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 登录响应 */
@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String username;
    private String role;
    private String deptName;
}
