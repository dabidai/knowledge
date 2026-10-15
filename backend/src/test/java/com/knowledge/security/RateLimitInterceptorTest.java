package com.knowledge.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitInterceptorTest {
    @RateLimit(maxRequests = 1, windowSeconds = 60) public void login() {}

    @Test void spoofedForwardedHeaderCannotBypassLimitAndExpiredCounterIsReclaimed() throws Exception {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000L);
        var limiter = new RateLimitInterceptor(clock);
        var handler = new HandlerMethod(this, getClass().getMethod("login"));
        var request = new MockHttpServletRequest(); request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "1.1.1.1");
        assertTrue(limiter.preHandle(request, new MockHttpServletResponse(), handler));
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "2.2.2.2");
        var denied = new MockHttpServletResponse();
        assertFalse(limiter.preHandle(request, denied, handler)); assertEquals(429, denied.getStatus());
        when(clock.millis()).thenReturn(61000L);
        assertTrue(limiter.preHandle(request, new MockHttpServletResponse(), handler));
        assertEquals(1, ((java.util.Map<?, ?>) ReflectionTestUtils.getField(limiter, "counters")).size());
    }

    @Test void trustedProxyUsesNearestUntrustedHopInsteadOfForgedLeftmostAddress() throws Exception {
        var limiter = new RateLimitInterceptor();
        ReflectionTestUtils.setField(limiter, "trustedProxies", List.of("127.0.0.1"));
        var handler = new HandlerMethod(this, getClass().getMethod("login"));
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.1.1.1, 10.0.0.2");
        assertTrue(limiter.preHandle(request, new MockHttpServletResponse(), handler));
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "2.2.2.2, 10.0.0.2");
        assertFalse(limiter.preHandle(request, new MockHttpServletResponse(), handler));
    }
}
