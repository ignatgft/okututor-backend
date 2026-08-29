package com.okututor.backend.security;

import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.util.Optional;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class GoogleProvisioner {

    private final UserRepository userRepository;

    public GoogleProvisioner(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Привязывает существующий LOCAL-аккаунт по email или создаёт новый
     * верифицированный. Роль при OAuth-регистрации ВСЕГДА STUDENT: стать
     * репетитором можно только через заявку и одобрение админом
     * (STUDENT -> заявка -> ADMIN -> APPROVED -> TUTOR).
     */
    @Transactional
    public User provision(OAuth2User oAuth2User) {
        String subject = oAuth2User.getName();
        String email = Optional.ofNullable(oAuth2User.getAttribute("email"))
                .map(Object::toString)
                .map(String::toLowerCase)
                .orElseThrow(() -> new IllegalStateException("Google profile has no email"));

        User user = userRepository.findByEmail(email).orElseGet(User::new);
        if (user.getId() == null) {
            user.setEmail(email);
            Object givenName = oAuth2User.getAttribute("given_name");
            Object familyName = oAuth2User.getAttribute("family_name");
            user.setFirstName(givenName != null ? givenName.toString() : null);
            user.setLastName(familyName != null ? familyName.toString() : null);
            // защита от эскалации привилегий: роль из query-параметра игнорируется
            user.setRole(Role.STUDENT);
            user.setVerified(true); // email подтверждён провайдером
            user.setProvider(User.AuthProvider.GOOGLE);
        }
        user.setGoogleSubject(subject);
        return userRepository.save(user);
    }
}
