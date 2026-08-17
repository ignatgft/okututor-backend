package com.okututor.backend.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(
    @Email @NotBlank String email,
    @NotBlank String password,
    @JsonProperty("repeat_password") @NotBlank String repeatPassword,
    @JsonProperty("full_name") @NotBlank String fullName
) {
}

