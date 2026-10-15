package com.knowledge.controller;

import com.knowledge.dto.*;
import com.knowledge.repository.UserRepository;
import com.knowledge.service.UserAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    private final UserRepository users;
    private final UserAccountService accounts;

    @GetMapping
    public ApiResponse<PagedResponse<UserResponse>> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("分页范围无效，size 须为 1–100");
        return ApiResponse.ok(new PagedResponse<>(users.findAll(PageRequest.of(page, size)).map(UserResponse::from)));
    }

    @PostMapping
    public ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest body) {
        return ApiResponse.ok(UserResponse.from(accounts.create(
                body.username(), body.password(), body.dept(), body.role(), false)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        accounts.delete(id);
        return ApiResponse.ok("已删除");
    }
}
