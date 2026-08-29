package com.okututor.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void splitFullNameHandlesSingleAndMultiPartNames() {
        assertThat(User.splitFullName("Test User")).containsExactly("Test", "User");
        assertThat(User.splitFullName("Cher")).containsExactly("Cher", null);
        assertThat(User.splitFullName("Anna Maria van Beethoven")).containsExactly("Anna", "Maria van Beethoven");
        assertThat(User.splitFullName(null)).containsExactly(null, null);
        assertThat(User.splitFullName("   ")).containsExactly(null, null);
    }

    @Test
    void fullNameFallsBackToEmailLocalPart() {
        User user = new User();
        user.setEmail("solo@example.com");
        assertThat(user.getFullName()).isEqualTo("solo");
    }
}
