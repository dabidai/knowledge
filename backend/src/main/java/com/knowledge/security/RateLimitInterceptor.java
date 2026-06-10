package com.knowledge.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求限流拦截器 —— 基于内存计数器的简单限流。
 * 检测 @RateLimit 注解，为每个 IP+方法 维护独立的计数器。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 计数器映射：key = "IP:方法签名:窗口起始秒" */
    private final Map<String, Integer> counterMap = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }

        RateLimit annotation = hm.getMethodAnnotation(RateLimit.class);
        if (annotation == null) {
            return true;
        }

        String clientIp = getClientIp(request);
        int window = annotation.windowSeconds();
        String methodKey = hm.getMethod().getName();
        long windowStart = System.currentTimeMillis() / 1000 / window;
        String counterKey = clientIp + ":" + methodKey + ":" + windowStart;

        int count = counterMap.merge(counterKey, 1, Integer::sum);

        if (count > annotation.maxRequests()) {
            log.warn("限流触发: IP={}, 方法={}, 窗口内请求={}", clientIp, methodKey, count);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}");
            return false;
        }

        return true;
    }

    /** 获取客户端真实 IP */
    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        String xr = request.getHeader("X-Real-IP");
        if (xr != null && !xr.isBlank()) return xr.trim();
        return request.getRemoteAddr();
    }
}
