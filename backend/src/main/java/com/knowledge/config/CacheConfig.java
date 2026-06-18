package com.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Redis 缓存配置 —— 按业务域定制 TTL 和序列化策略。
 *
 * <p>缓存域及过期策略：
 * <ul>
 *   <li>search —— 搜索结果，10 分钟（避免回答过时）</li>
 *   <li>browseTree —— 文档目录树，30 分钟（变动不频繁）</li>
 *   <li>graphOverview —— 图谱概览，15 分钟</li>
 *   <li>importTasks —— 导入历史，2 分钟（需及时反映新任务）</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 默认缓存配置。
     * 使用 Jackson JSON 序列化值，禁止缓存 null，默认 TTL 1 小时。
     *
     * @param objectMapper Spring 管理的 ObjectMapper 实例
     * @return 全局默认的 RedisCacheConfiguration
     */
    @Bean
    public RedisCacheConfiguration defaultCacheConfig(ObjectMapper objectMapper) {
        // 必须复制 ObjectMapper 并开启 DefaultTyping，否则传入 GenericJackson2JsonRedisSerializer 的
        // 自定义 mapper 不会自动写入 @class 类型信息，反序列化时会得到 LinkedHashMap 而非原始类型
        ObjectMapper cacheMapper = objectMapper.copy();
        cacheMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.NON_FINAL);

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(cacheMapper);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                .disableCachingNullValues();
    }

    /**
     * 按缓存名定制 TTL，继承全局 Jackson 序列化配置。
     *
     * @param defaultConfig 全局默认配置（含 Jackson 序列化器）
     * @return RedisCacheManagerBuilderCustomizer 定制器
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer cacheCustomizer(
            RedisCacheConfiguration defaultConfig) {
        return builder -> builder
                .withCacheConfiguration("search",
                        defaultConfig.entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration("browseTree",
                        defaultConfig.entryTtl(Duration.ofMinutes(30)))
                .withCacheConfiguration("graphOverview",
                        defaultConfig.entryTtl(Duration.ofMinutes(15)))
                .withCacheConfiguration("importTasks",
                        defaultConfig.entryTtl(Duration.ofMinutes(2)));
    }
}
