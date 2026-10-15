package com.knowledge.security;

import com.knowledge.entity.User;

/** 服务端认证身份决定的文档级读取范围。 */
public record AccessScope(boolean admin, String department) {
    public static AccessScope of(User user) {
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new org.springframework.security.access.AccessDeniedException("用户不可用");
        }
        String department = user.getDepartment() == null ? null : user.getDepartment().getName();
        return new AccessScope("admin".equalsIgnoreCase(user.getRole()),
                department == null || department.isBlank() ? null : department);
    }

    public boolean allows(String ownerDepartment, Boolean publicResource) {
        return admin || Boolean.TRUE.equals(publicResource)
                || (department != null && department.equals(ownerDepartment));
    }
}
