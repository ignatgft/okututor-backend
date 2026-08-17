package com.okututor.backend.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(
    @NotBlank String fullName,
    @Email @NotBlank String email,
    String phone,
    String location,
    String bio,
    String telegram,
    String instagram,
    String whatsapp,
    String avatar
) {
}

