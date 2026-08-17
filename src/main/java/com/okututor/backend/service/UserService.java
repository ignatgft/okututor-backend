package com.okututor.backend.service;

import com.okututor.backend.dto.user.UpdateProfileRequest;
import com.okututor.backend.dto.user.UserProfileResponse;
import com.okututor.backend.entity.UserEntity;
import com.okututor.backend.exception.ApiBadRequestException;
import com.okututor.backend.exception.ApiNotFoundException;
import com.okututor.backend.repository.UserRepository;
import com.okututor.backend.security.JwtUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

  private final UserRepository userRepository;

  public UserService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  public UserProfileResponse getUserProfile(String userId) {
    return UserProfileResponse.fromEntity(findUser(userId));
  }

  @Transactional
  public UserProfileResponse updateProfile(String userId, UpdateProfileRequest request, JwtUserPrincipal principal) {
    if (principal == null || !principal.getId().equals(userId)) {
      throw new ApiBadRequestException("You can only update your own profile");
    }

    UserEntity user = findUser(userId);
    if (!user.getEmail().equalsIgnoreCase(request.email())) {
      userRepository.findByEmail(request.email()).ifPresent(existing -> {
        if (!existing.getId().equals(userId)) {
          throw new ApiBadRequestException("Email already exists");
        }
      });
    }
    user.setFullName(request.fullName());
    user.setEmail(request.email());
    user.setPhone(request.phone());
    user.setLocation(request.location());
    user.setBio(request.bio());
    user.setTelegram(request.telegram());
    user.setInstagram(request.instagram());
    user.setWhatsapp(request.whatsapp());
    user.setAvatar(request.avatar());
    return UserProfileResponse.fromEntity(userRepository.save(user));
  }

  private UserEntity findUser(String userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new ApiNotFoundException("User not found"));
  }
}
