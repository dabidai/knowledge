package com.knowledge.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j 图数据库驱动配置 —— 通过 Bolt 协议连接 Neo4j 服务。
 * 驱动实例为应用级单例，线程安全，支持连接池。
 */
@Configuration
public class Neo4jConfig {

    /** Bolt 协议连接地址，如 bolt://localhost:7687 */
    @Value("${neo4j.uri}")
    private String uri;

    /** 认证用户名 */
    @Value("${neo4j.username}")
    private String username;

    /** 认证密码 */
    @Value("${neo4j.password}")
    private String password;

    /**
     * 创建 Neo4j 驱动实例。
     * 使用 Basic Auth 认证，驱动内部管理连接池。
     *
     * @return Neo4j Bolt Driver 实例
     */
    @Bean
    public Driver neo4jDriver() {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }
}
