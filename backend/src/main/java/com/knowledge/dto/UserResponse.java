package com.knowledge.dto;

import com.knowledge.entity.User;

/** 所有用户信息响应共用安全 DTO，不序列化持久化实体或密码。 */
public record UserResponse(Long id, String username, String role, String deptName,
                           DepartmentInfo department, boolean enabled) {
    public record DepartmentInfo(Long id, String name) {}

    public static UserResponse from(User user) {
        var dept = user.getDepartment();
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(),
                dept == null ? null : dept.getName(),
                dept == null ? null : new DepartmentInfo(dept.getId(), dept.getName()),
                Boolean.TRUE.equals(user.getEnabled()));
    }
}
