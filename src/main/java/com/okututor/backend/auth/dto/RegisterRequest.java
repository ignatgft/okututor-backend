package com.okututor.backend.auth.dto;

import com.okututor.backend.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank String repeat_password,
        @NotBlank @Size(max = 200) String full_name,
        Role role
) {

    public Role roleOrDefault() {
        if (role == null) {
            return Role.STUDENT;
        }
        // самостоятельно выбрать можно только STUDENT/TUTOR; остальное деградирует до STUDENT
        return switch (role) {
            case STUDENT, TUTOR -> role;
            default -> Role.STUDENT;
        };
    }

    public String normalizedName() {
        return full_name == null ? null : full_name.trim().replaceAll("\\s+", " ");
    }

    public String normalizedEmail() {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
