package com.okututor.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Эскалация ролей через Google OAuth запрещена: даже если фронт передал
 * ?role=TUTOR, новый OAuth-аккаунт всегда создаётся как STUDENT.
 */
class GoogleProvisionerTest {

    private UserRepository userRepository;
    private GoogleProvisioner provisioner;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        provisioner = new GoogleProvisioner(userRepository);
    }

    private OAuth2User googleUser(String email) {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getName()).thenReturn("google-sub-123");
        when(principal.getAttribute("email")).thenReturn(email);
        when(principal.getAttribute("given_name")).thenReturn("New");
        when(principal.getAttribute("family_name")).thenReturn("User");
        return principal;
    }

    @Test
    void newAccountIsAlwaysStudentEvenIfTutorRoleRequested() {
        // раньше handler прокидывал ?role=TUTOR сюда — теперь роль игнорируется
        User user = provisioner.provision(googleUser("new.user@gmail.com"));

        assertThat(user.getRole()).isEqualTo(Role.STUDENT);
        assertThat(user.isVerified()).isTrue();
        assertThat(user.getProvider()).isEqualTo(User.AuthProvider.GOOGLE);
    }

    @Test
    void existingLocalAccountIsLinkedWithoutRoleChange() {
        User existing = new User();
        existing.setId(java.util.UUID.randomUUID()); // имитируем сохранённую строку в БД
        existing.setEmail("existing@test.com");
        existing.setRole(Role.TUTOR); // уже одобренный репетитор
        when(userRepository.findByEmail("existing@test.com")).thenReturn(Optional.of(existing));

        User linked = provisioner.provision(googleUser("existing@test.com"));

        assertThat(linked).isSameAs(existing);
        assertThat(linked.getRole()).isEqualTo(Role.TUTOR); // роль не трогаем
        assertThat(linked.getGoogleSubject()).isEqualTo("google-sub-123");
    }

    @Test
    void missingEmailFailsFast() {
        OAuth2User noEmail = mock(OAuth2User.class);
        when(noEmail.getName()).thenReturn("sub-1");
        when(noEmail.getAttribute("email")).thenReturn(null);

        assertThatThrownBy(() -> provisioner.provision(noEmail))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email");
    }
}
