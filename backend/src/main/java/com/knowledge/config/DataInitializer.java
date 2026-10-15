package com.knowledge.config;

import com.knowledge.entity.Department;
import com.knowledge.repository.DepartmentRepository;
import com.knowledge.repository.UserRepository;
import com.knowledge.service.GraphBuildService;
import com.knowledge.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** 唯一的首次管理员创建入口：空库显式配置，已有用户时不重置。 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataInitializer {
    private final DepartmentRepository deptRepo;
    private final UserRepository userRepo;
    private final UserAccountService accounts;
    private final GraphBuildService graph;
    private final TransactionTemplate transactions;

    @Value("${bootstrap.admin.username:}")
    private String username;
    @Value("${bootstrap.admin.password:}")
    private String password;
    @Value("${bootstrap.admin.department:信息技术部}")
    private String department;

    @Bean
    public ApplicationRunner initDefaultData() {
        return args -> {
            if (userRepo.count() > 0) {
                log.info("已有用户，跳过首次管理员初始化");
                return;
            }
            if (username == null || username.isBlank() || password == null || password.isBlank())
                throw new IllegalStateException("首次启动请配置 BOOTSTRAP_ADMIN_USERNAME 和 BOOTSTRAP_ADMIN_PASSWORD");
            UserAccountService.validate(username, password, department, "admin");
            transactions.executeWithoutResult(status -> {
                for (String name : List.of("信息技术部", "办公室", "研究室")) {
                    if (!deptRepo.existsByName(name)) deptRepo.save(Department.builder().name(name).build());
                    graph.createDepartment(name);
                }
                accounts.create(username, password, department, "admin", true);
            });
            log.info("首次管理员已创建，后续启动不覆盖账号");
        };
    }
}
