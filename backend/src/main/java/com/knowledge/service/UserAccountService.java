package com.knowledge.service;

import com.knowledge.entity.Department;
import com.knowledge.entity.User;
import com.knowledge.repository.DepartmentRepository;
import com.knowledge.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;

/** API、CSV 和首次初始化采用相同的账号规则。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAccountService {
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final PasswordEncoder passwords;
    private final GraphBuildService graph;

    public static void validate(String username, String password, String department, String role) {
        if (username == null || !username.matches("[\\p{L}\\p{N}_.@-]{1,50}"))
            throw new IllegalArgumentException("用户名须为 1–50 位字母、数字或 _.@-，不能包含空格");
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("密码至少 8 个字符，UTF-8 编码不得超过 72 字节");
        if (department == null || department.isBlank() || department.length() > 100)
            throw new IllegalArgumentException("部门名称不能为空且不能超过 100 字符");
        if (!"admin".equals(role) && !"default".equals(role))
            throw new IllegalArgumentException("角色只能是 admin 或 default");
    }

    @Transactional
    public User create(String username, String password, String deptName, String role, boolean createDepartment) {
        validate(username, password, deptName, role);
        if (users.existsByUsername(username)) throw new IllegalArgumentException("用户名已存在");
        Department dept = departments.findByName(deptName).orElseGet(() -> {
            if (!createDepartment) throw new IllegalArgumentException("部门不存在");
            return departments.save(Department.builder().name(deptName).build());
        });
        User user = users.save(User.builder().username(username).password(passwords.encode(password))
                .role(role).department(dept).build());
        syncGraph(user);
        return user;
    }

    /** 已存在且归属/角色相同则跳过，不修改密码；冲突明确拒绝。 */
    @Transactional
    public boolean importUser(String username, String password, String deptName, String role) {
        validate(username, password, deptName, role);
        var existing = users.findByUsername(username);
        if (existing.isPresent()) {
            User user = existing.get();
            if (!role.equals(user.getRole()) || user.getDepartment() == null
                    || !deptName.equals(user.getDepartment().getName()))
                throw new IllegalArgumentException("已存在用户的角色或部门与 CSV 不一致，禁止导入覆盖");
            log.info("跳过已存在账号，保留其密码与状态: {}", username);
            syncGraph(user);
            return false;
        }
        create(username, password, deptName, role, true);
        return true;
    }

    @Transactional
    public void delete(Long id) {
        User user = users.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if ("admin".equals(user.getRole())) throw new IllegalArgumentException("管理员账号不可直接删除");
        users.delete(user);
    }

    private void syncGraph(User user) {
        graph.createDepartment(user.getDepartment().getName());
        graph.createUser(user.getUsername(), user.getRole(), user.getDepartment().getName());
    }
}
