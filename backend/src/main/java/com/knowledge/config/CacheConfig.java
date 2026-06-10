package com.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/** Redis 缓存配置 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 默认缓存配置：1 小时 TTL，JSON 序列化 */
    @Bean
    public RedisCacheConfiguration defaultCacheConfig(ObjectMapper objectMapper) {
        Jackson2JsonRedisSerializer<Object> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                .disableCachingNullValues();
    }

    /** 按缓存名定制 TTL */
    @Bean
    public RedisCacheManagerBuilderCustomizer cacheCustomizer() {
        return builder -> builder
                // 搜索结果缓存 10 分钟
                .withCacheConfiguration("search",
                        defaultCacheConfigCustom(Duration.ofMinutes(10)))
                // 文档树缓存 30 分钟
                .withCacheConfiguration("browseTree",
                        defaultCacheConfigCustom(Duration.ofMinutes(30)))
                // 图谱概览缓存 15 分钟
                .withCacheConfiguration("graphOverview",
                        defaultCacheConfigCustom(Duration.ofMinutes(15)))
                // 导入历史缓存 2 分钟
                .withCacheConfiguration("importTasks",
                        defaultCacheConfigCustom(Duration.ofMinutes(2)));
    }

    private RedisCacheConfiguration defaultCacheConfigCustom(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues();
    }
}
