package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.entity.Department;
import com.knowledge.repository.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 部门管理控制器 */
@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentRepository deptRepo;

    /** 部门列表（所有登录用户可见，用于导入时选择目标部门） */
    @GetMapping
    public ApiResponse<List<Department>> list() {
        return ApiResponse.ok(deptRepo.findAll());
    }

    /** 新增部门 (仅 admin) */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Department> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ApiResponse.error(400, "部门名称不能为空");
        }
        if (deptRepo.existsByName(name)) {
            return ApiResponse.error(400, "部门已存在: " + name);
        }
        Department dept = deptRepo.save(Department.builder().name(name).build());
        return ApiResponse.ok(dept);
    }

    /** 删除部门 (仅 admin) */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<String> delete(@PathVariable Long id) {
        Department dept = deptRepo.findById(id).orElse(null);
        if (dept == null) {
            return ApiResponse.error(404, "部门不存在");
        }
        deptRepo.delete(dept);
        return ApiResponse.ok("删除成功");
    }
}
