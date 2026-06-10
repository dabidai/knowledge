package com.knowledge.security;

import java.lang.annotation.*;

/**
 * 请求限流注解。
 * 标注在 Controller 方法上，限制该接口的访问频率。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 时间窗口内的最大请求次数 */
    int maxRequests() default 20;

    /** 时间窗口（秒） */
    int windowSeconds() default 60;
}
