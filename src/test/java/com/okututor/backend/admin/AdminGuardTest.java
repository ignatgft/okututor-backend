package com.okututor.backend.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.security.UserPrincipal;
import org.junit.jupiter.api.Test;

class AdminGuardTest {

    private static UserPrincipal principal(com.okututor.backend.user.Role role) {
        return new UserPrincipal(java.util.UUID.randomUUID(), "u@test.com", role);
    }

    @Test
    void adminGuardRejectsAnonymousAndNonAdmins() {
        assertThatThrownBy(() -> AdminController.requireAdmin(null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("UNAUTHORIZED");

        for (com.okututor.backend.user.Role role : new com.okututor.backend.user.Role[]{
                com.okututor.backend.user.Role.STUDENT, com.okututor.backend.user.Role.TUTOR}) {
            assertThatThrownBy(() -> AdminController.requireAdmin(principal(role)))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void adminGuardAcceptsAdminAndSuperAdmin() {
        assertThatCode(() -> AdminController.requireAdmin(principal(com.okututor.backend.user.Role.ADMIN)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AdminController.requireAdmin(principal(com.okututor.backend.user.Role.SUPER_ADMIN)))
                .doesNotThrowAnyException();
    }

    @Test
    void superAdminOnlyForRoleManagement() {
        assertThatThrownBy(() -> AdminController.requireSuperAdmin(principal(com.okututor.backend.user.Role.ADMIN)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("SUPER_ADMIN");

        assertThatCode(() -> AdminController.requireSuperAdmin(
                principal(com.okututor.backend.user.Role.SUPER_ADMIN))).doesNotThrowAnyException();
    }
}
