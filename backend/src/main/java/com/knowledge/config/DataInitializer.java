package com.knowledge.config;

import com.knowledge.entity.Department;
import com.knowledge.entity.User;
import com.knowledge.repository.DepartmentRepository;
import com.knowledge.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * 数据初始化器 —— 首次启动时创建默认部门和 admin 账号。
 *
 * <p>仅在用户表为空时执行（幂等），已初始化的系统不受影响。
 * 默认部门：信息技术部、办公室、研究室。
 * 默认 admin：admin / admin123，归属信息技术部。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private final DepartmentRepository deptRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    /** 默认部门列表 */
    private static final List<String> DEFAULT_DEPTS = List.of("信息技术部", "办公室", "研究室");

    /** 默认管理员账号 */
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    private static final String DEFAULT_ADMIN_DEPT = "信息技术部";

    @Bean
    public ApplicationRunner initDefaultData() {
        return args -> {
            // 1. 默认部门 —— 始终创建（幂等），确保新增用户时可选择部门
            for (String deptName : DEFAULT_DEPTS) {
                if (!deptRepo.existsByName(deptName)) {
                    deptRepo.save(Department.builder().name(deptName).build());
                    log.info("  ✅ 创建部门: {}", deptName);
                }
            }

            // 2. 默认管理员 —— 仅在无用户时创建
            if (userRepo.count() > 0) {
                log.info("已有 {} 个用户，跳过管理员创建", userRepo.count());
                return;
            }

            Department adminDept = deptRepo.findByName(DEFAULT_ADMIN_DEPT)
                    .orElseGet(() -> deptRepo.save(
                            Department.builder().name(DEFAULT_ADMIN_DEPT).build()));

            userRepo.save(User.builder()
                    .username(DEFAULT_ADMIN_USERNAME)
                    .password(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD))
                    .role("admin")
                    .department(adminDept)
                    .build());

            log.info("  ✅ 创建管理员: {} / {}", DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
        };
    }
}
