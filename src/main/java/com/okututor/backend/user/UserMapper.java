package com.okututor.backend.user;

import com.okututor.backend.user.dto.PublicUserResponse;
import com.okututor.backend.user.dto.UserResponse;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.getAvatarUrl(),
                user.isVerified(),
                user.isBlocked(),
                user.getCreatedAt(),
                user.getBio(),
                user.getPhone(),
                user.getLocation(),
                user.getExperienceYears(),
                user.getEducation());
    }

    public PublicUserResponse toPublicResponse(User user) {
        return new PublicUserResponse(
                user.getId(),
                user.getFullName(),
                user.getFirstName(),
                user.getLastName(),
                user.getAvatarUrl(),
                user.getRole().name(),
                user.getBio(),
                user.getLocation(),
                user.getExperienceYears(),
                user.getEducation());
    }
}
