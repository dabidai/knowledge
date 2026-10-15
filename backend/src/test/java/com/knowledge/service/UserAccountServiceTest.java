package com.knowledge.service;

import com.knowledge.entity.*;
import com.knowledge.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class UserAccountServiceTest {
    final UserRepository users = mock(UserRepository.class);
    final DepartmentRepository departments = mock(DepartmentRepository.class);
    final GraphBuildService graph = mock(GraphBuildService.class);
    final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    final UserAccountService service = new UserAccountService(users, departments, passwords, graph);
    final Department dept = Department.builder().id(1L).name("A").build();

    @Test void apiAndCsvApplySamePasswordAndRoleRulesBeforeWrites() {
        for (String password : new String[]{"short", "中".repeat(25)}) {
            assertThrows(IllegalArgumentException.class, () -> service.create("user", password, "A", "default", false));
            assertThrows(IllegalArgumentException.class, () -> service.importUser("user", password, "A", "default"));
        }
        assertThrows(IllegalArgumentException.class, () -> service.create("user", "password123", "A", "root", false));
        verifyNoInteractions(users, departments, graph);
    }

    @Test void createsWithHashedPasswordAndActualGraphIdentity() {
        when(departments.findByName("A")).thenReturn(Optional.of(dept));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        User user = service.create("user", "password123", "A", "default", false);
        assertTrue(passwords.matches("password123", user.getPassword()));
        verify(graph).createUser("user", "default", "A");
    }

    @Test void repeatedCsvDoesNotResetCredentialsOrGraphScope() {
        User existing = User.builder().id(1L).username("user").password("original-hash")
                .role("default").department(dept).enabled(false).build();
        when(users.findByUsername("user")).thenReturn(Optional.of(existing));
        assertFalse(service.importUser("user", "different-password", "A", "default"));
        assertEquals("original-hash", existing.getPassword());
        assertFalse(existing.getEnabled());
        verify(users, never()).save(any());
        assertThrows(IllegalArgumentException.class, () -> service.importUser("user", "password123", "B", "admin"));
        verify(graph, never()).createUser("user", "admin", "B");
    }

    @Test void adminDeletionIsRejectedOnServer() {
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).role("admin").build()));
        assertThrows(IllegalArgumentException.class, () -> service.delete(1L));
        verify(users, never()).delete(any(User.class));
        User member = User.builder().id(2L).role("default").build();
        when(users.findById(2L)).thenReturn(Optional.of(member));
        service.delete(2L);
        verify(users).delete(member);
    }
}
