package com.knowledge.service;

import com.knowledge.config.DataInitializer;
import com.knowledge.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BootstrapTest {
    final UserRepository users = mock(UserRepository.class);
    final DepartmentRepository departments = mock(DepartmentRepository.class);
    final UserAccountService accounts = mock(UserAccountService.class);
    final GraphBuildService graph = mock(GraphBuildService.class);
    final TransactionTemplate transactions = mock(TransactionTemplate.class);
    final DataInitializer initializer = new DataInitializer(departments, users, accounts, graph, transactions);

    @Test void existingUsersSkipAllInitializationAndDoNotRequireBootstrapPassword() throws Exception {
        when(users.count()).thenReturn(1L);
        initializer.initDefaultData().run(null);
        verifyNoInteractions(departments, accounts, graph, transactions);
    }

    @Test void emptyDatabaseRequiresConfigurationBeforeAnyWrite() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> initializer.initDefaultData().run(null));
        assertTrue(error.getMessage().contains("BOOTSTRAP_ADMIN_PASSWORD"));
        verifyNoInteractions(departments, accounts, graph, transactions);
    }

    @Test void configuredBootstrapCreatesOnlyExplicitAdministrator() throws Exception {
        ReflectionTestUtils.setField(initializer, "username", "owner");
        ReflectionTestUtils.setField(initializer, "password", "chosen-password");
        ReflectionTestUtils.setField(initializer, "department", "A");
        doAnswer(i -> { ((Consumer<org.springframework.transaction.TransactionStatus>) i.getArgument(0))
                .accept(mock(org.springframework.transaction.TransactionStatus.class)); return null; })
                .when(transactions).executeWithoutResult(any());
        initializer.initDefaultData().run(null);
        verify(accounts).create("owner", "chosen-password", "A", "admin", true);
    }
}
