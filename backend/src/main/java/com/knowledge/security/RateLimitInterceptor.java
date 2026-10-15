package com.knowledge.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 单实例有界限流；仅信任显式配置的代理，不采信任意转发头。 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private record Counter(long expiresAt, int count) {}
    private final Map<String, Counter> counters = new HashMap<>();
    private final Clock clock;
    private long nextCleanup;
    private static final int MAX_COUNTERS = 10_000;

    @Value("${security.rate-limit.trusted-proxies:}")
    private List<String> trustedProxies = List.of();

    public RateLimitInterceptor() { this(Clock.systemUTC()); }
    RateLimitInterceptor(Clock clock) { this.clock = clock; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method)) return true;
        RateLimit rule = method.getMethodAnnotation(RateLimit.class);
        if (rule == null) return true;
        long now = clock.millis();
        long windowMs = rule.windowSeconds() * 1000L;
        String key = clientIp(request) + ":" + method.getMethod().toGenericString() + ":" + now / windowMs;
        if (allow(key, now, windowMs, rule.maxRequests())) return true;
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(Math.max(1, (windowMs - now % windowMs + 999) / 1000)));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}");
        return false;
    }

    private synchronized boolean allow(String key, long now, long windowMs, int limit) {
        if (now >= nextCleanup || counters.size() >= MAX_COUNTERS) {
            counters.values().removeIf(c -> c.expiresAt <= now);
            nextCleanup = now + 1000;
        }
        Counter counter = counters.get(key);
        if (counter == null && counters.size() >= MAX_COUNTERS) return false;
        int count = counter == null ? 1 : Math.min(counter.count + 1, limit + 1);
        counters.put(key, new Counter((now / windowMs + 1) * windowMs, count));
        return count <= limit;
    }

    private String clientIp(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (!trustedProxies.contains(address)) return address;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return address;
        String[] chain = forwarded.split(",");
        // 从可信代理最近的一跳向左检查，不使用客户端自行添加的最左地址。
        for (int i = chain.length - 1; i >= 0 && trustedProxies.contains(address); i--) {
            String candidate = chain[i].trim();
            if (candidate.isBlank() || candidate.length() > 45 || !candidate.matches("[0-9a-fA-F:.]+")) break;
            address = candidate;
        }
        return address;
    }
}
