package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.entity.Department;
import com.knowledge.entity.User;
import com.knowledge.repository.DepartmentRepository;
import com.knowledge.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 用户管理控制器 (仅 admin) */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserRepository userRepo;
    private final DepartmentRepository deptRepo;
    private final PasswordEncoder passwordEncoder;

    /** 初始化管理员账号（系统首次使用） */
    @PostMapping("/init")
    @PreAuthorize("permitAll()")
    public ApiResponse<Map<String, String>> initAdmin(@RequestBody Map<String, String> body) {
        if (userRepo.count() > 0) {
            return ApiResponse.error(400, "系统已初始化，请使用管理员账号登录后通过 /api/users 管理用户");
        }

        String username = body.get("username");
        String password = body.get("password");
        String deptName = body.get("dept");

        Department dept = deptRepo.findByName(deptName)
                .orElseGet(() -> deptRepo.save(Department.builder().name(deptName).build()));

        userRepo.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .role("admin")
                .department(dept)
                .build());

        return ApiResponse.ok("管理员创建成功", Map.of("username", username));
    }

    /** 用户列表 */
    @GetMapping
    public ApiResponse<List<User>> list() {
        return ApiResponse.ok(userRepo.findAll());
    }

    /** 新增用户 */
    @PostMapping
    public ApiResponse<User> create(@RequestBody Map<String, String> body) {
        Department dept = deptRepo.findByName(body.get("dept"))
                .orElseThrow(() -> new IllegalArgumentException("部门不存在: " + body.get("dept")));

        User user = User.builder()
                .username(body.get("username"))
                .password(passwordEncoder.encode(body.get("password")))
                .role(body.getOrDefault("role", "default"))
                .department(dept)
                .build();

        return ApiResponse.ok(userRepo.save(user));
    }

    /** 删除用户 */
    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        userRepo.deleteById(id);
        return ApiResponse.ok("已删除");
    }
}
