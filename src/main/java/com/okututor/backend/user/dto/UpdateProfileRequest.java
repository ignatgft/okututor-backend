package com.okututor.backend.user.dto;

import jakarta.validation.constraints.Size;

/**
 * тело PUT /users/me — зеркалит ключи formData из Profile.jsx.
 */
public record UpdateProfileRequest(
        @Size(max = 200) String fullName,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @Size(max = 4000) String bio,
        @Size(max = 40) String phone,
        @Size(max = 255) String location,
        Integer experience_years,
        @Size(max = 500) String education
) {
}
