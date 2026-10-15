package com.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String dept,
        @Pattern(regexp = "admin|default", message = "角色只能是 admin 或 default") String role) {
    public CreateUserRequest {
        if (role == null) role = "default";
    }
}
