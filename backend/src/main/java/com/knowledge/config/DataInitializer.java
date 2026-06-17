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
            // 幂等：已有用户则跳过
            if (userRepo.count() > 0) {
                log.info("系统已初始化，跳过默认数据创建 (已有 {} 个用户)", userRepo.count());
                return;
            }

            log.info("━━━ 首次启动，开始初始化默认数据 ━━━");

            // 1. 创建默认部门
            for (String deptName : DEFAULT_DEPTS) {
                if (!deptRepo.existsByName(deptName)) {
                    deptRepo.save(Department.builder().name(deptName).build());
                    log.info("  ✅ 创建部门: {}", deptName);
                }
            }

            // 2. 确保 admin 所属部门存在
            Department adminDept = deptRepo.findByName(DEFAULT_ADMIN_DEPT)
                    .orElseGet(() -> deptRepo.save(
                            Department.builder().name(DEFAULT_ADMIN_DEPT).build()));

            // 3. 创建默认管理员
            userRepo.save(User.builder()
                    .username(DEFAULT_ADMIN_USERNAME)
                    .password(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD))
                    .role("admin")
                    .department(adminDept)
                    .build());

            log.info("  ✅ 创建管理员: {} / {}", DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
            log.info("━━━ 初始化完成，请使用 admin/admin123 登录 ━━━");
        };
    }
}
