package com.okututor.backend.dto.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.okututor.backend.entity.UserEntity;

public record UserProfileResponse(
    String uid,
    String fullName,
    String email,
    String phone,
    String location,
    String bio,
    String telegram,
    String instagram,
    String whatsapp,
    String avatar,
    @JsonProperty("photoURL") String photoURL,
    @JsonProperty("displayName") String displayName
) {

  public static UserProfileResponse fromEntity(UserEntity user) {
    String avatar = user.getAvatar();
    return new UserProfileResponse(
        user.getId(),
        user.getFullName(),
        user.getEmail(),
        user.getPhone(),
        user.getLocation(),
        user.getBio(),
        user.getTelegram(),
        user.getInstagram(),
        user.getWhatsapp(),
        avatar,
        avatar,
        user.getFullName()
    );
  }
}

