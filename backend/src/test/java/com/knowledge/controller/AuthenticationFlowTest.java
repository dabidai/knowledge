package com.knowledge.controller;

import com.knowledge.common.GlobalExceptionHandler;
import com.knowledge.config.SecurityConfig;
import com.knowledge.entity.*;
import com.knowledge.repository.UserRepository;
import com.knowledge.security.*;
import com.knowledge.service.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真正经过 SecurityFilterChain 的请求测试，不加载业务应用/数据源。 */
@WebMvcTest
@org.springframework.test.context.ActiveProfiles("test")
@ContextConfiguration(classes = {AuthController.class, UserController.class, SecurityConfig.class,
        JwtAuthFilter.class, JwtUtil.class, AuthService.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {"jwt.secret=authentication-test-secret-at-least-32-bytes", "jwt.expiration=3600000"})
class AuthenticationFlowTest {
    static final String SECRET = "authentication-test-secret-at-least-32-bytes";
    @Autowired MockMvc mvc;
    @Autowired JwtUtil tokens;
    @Autowired PasswordEncoder passwords;
    @MockBean UserRepository users;
    @MockBean UserAccountService accounts;

    User user(long id, String role, boolean enabled) {
        return User.builder().id(id).username("same-name").role(role).enabled(enabled)
                .password(passwords.encode("password123")).build();
    }

    @Test void loginAndMeUseSafeDtoAndWorkWithoutDepartment() throws Exception {
        User user = user(7L, "default", true);
        when(users.findByUsername("same-name")).thenReturn(Optional.of(user));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"same-name\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.id").value(7))
                .andExpect(jsonPath("$.data.user.password").doesNotExist()).andExpect(jsonPath("$.data.token").isString());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tokens.generateToken(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test void disabledAccountCannotLoginOrUseOldToken() throws Exception {
        User user = user(7L, "default", false);
        when(users.findByUsername("same-name")).thenReturn(Optional.of(user));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"same-name\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tokens.generateToken(7L)))
                .andExpect(status().isUnauthorized());
    }

    @Test void deletedAndRecreatedUsernameDoesNotTransferOldIdentity() throws Exception {
        when(users.findById(7L)).thenReturn(Optional.empty());
        when(users.findById(8L)).thenReturn(Optional.of(user(8L, "admin", true)));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tokens.generateToken(7L)))
                .andExpect(status().isUnauthorized());
        verify(users, never()).findByUsername(anyString());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tokens.generateToken(8L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(8));
    }

    String signed(String subject, Date expiry) {
        var builder = Jwts.builder().subject(subject);
        if (expiry != null) builder.expiration(expiry);
        return builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @Test void invalidExpiredMissingExpiryAndNonIdTokensAre401() throws Exception {
        for (String token : List.of("broken", new JwtUtil("different-signing-key-at-least-32-bytes", 60000).generateToken(7L),
                signed("same-name", new Date(System.currentTimeMillis() + 60000)),
                signed("7", new Date(1)), signed("7", null), signed("-1", new Date(System.currentTimeMillis() + 60000)),
                signed("999999999999999999999999999", new Date(System.currentTimeMillis() + 60000)))) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @Test void currentRoleControlsManagementAndUserResponsesExcludePassword() throws Exception {
        User user = user(7L, "default", true);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        String token = tokens.generateToken(7L);
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        user.setRole("admin");
        when(users.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(user)));
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].password").doesNotExist());
        user.setDepartment(Department.builder().id(1L).name("新部门").build());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.deptName").value("新部门"));
    }

    @Test void invalidUserRequestAndPagingAreRejectedBeforeMutation() throws Exception {
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "admin", true)));
        String token = tokens.generateToken(7L);
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"username\":\"u\",\"password\":\"password123\",\"dept\":\"A\",\"role\":\"root\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/users?size=100000").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        verifyNoInteractions(accounts);
    }

    @Test void oldPublicInitializationRouteNoLongerExists() throws Exception {
        mvc.perform(post("/api/users/init").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "admin", true)));
        mvc.perform(post("/api/users/init").header("Authorization", "Bearer " + tokens.generateToken(7L)))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(accounts);
    }

    @Test void weakOrEmptySigningKeyFailsClearly() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil("", 1000));
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil("short", 1000));
    }

    @Test void corsAcceptsOnlyConfiguredDevelopmentOrigins() throws Exception {
        mvc.perform(options("/api/auth/me").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")).andExpect(status().isOk());
        mvc.perform(options("/api/auth/me").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "GET")).andExpect(status().isForbidden());
    }
}
