package com.knowledge.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** JPA 切片测试不加载应用启动器及外部服务。 */
@Configuration(proxyBeanMethods = false)
@EntityScan("com.knowledge.entity")
@EnableJpaRepositories("com.knowledge.repository")
class JpaTestConfiguration {}
