package com.knowledge.dto;

/** 登录响应 */
public record LoginResponse(String token, UserResponse user) {}
