package com.okututor.backend.user.dto;

import com.okututor.backend.user.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * канонический payload пользователя (форма из mockData.js + поля профиля из UI):
 * { id, email, full_name, first_name, last_name, role, avatar, verified, created_at,
 *   bio, phone, location, experience_years, education }.
 */
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        String firstName,
        String lastName,
        Role role,
        String avatar,
        boolean verified,
        boolean blocked,
        Instant createdAt,
        String bio,
        String phone,
        String location,
        Integer experienceYears,
        String education
) {
}
