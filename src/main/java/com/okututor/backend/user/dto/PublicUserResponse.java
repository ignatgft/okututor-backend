package com.okututor.backend.user.dto;

import java.util.UUID;

/** публичные данные пользователя для карточек/профилей репетиторов (без phone/email/статусов). */
public record PublicUserResponse(
        UUID id,
        String fullName,
        String firstName,
        String lastName,
        String avatar,
        String role,
        String bio,
        String location,
        Integer experienceYears,
        String education
) {
}
