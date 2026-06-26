package com.knowledge.controller;

import com.knowledge.dto.ApiResponse;
import com.knowledge.dto.PagedResponse;
import com.knowledge.entity.Department;
import com.knowledge.repository.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    /** 部门列表（分页，默认 size=50 保证下拉框通常一次拿全） */
    @GetMapping
    public ApiResponse<PagedResponse<Department>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<Department> result = deptRepo.findAll(PageRequest.of(page, size));
        return ApiResponse.ok(new PagedResponse<>(result));
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
