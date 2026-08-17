package com.okututor.backend.controller;

import com.okututor.backend.dto.user.UpdateProfileRequest;
import com.okututor.backend.dto.user.UserProfileResponse;
import com.okututor.backend.security.JwtUserPrincipal;
import com.okututor.backend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping("/user/{userId}")
  public UserProfileResponse userById(@PathVariable String userId) {
    return userService.getUserProfile(userId);
  }

  @PutMapping("/user/{userId}/profile")
  public UserProfileResponse updateProfile(@PathVariable String userId,
      @Valid @RequestBody UpdateProfileRequest request,
      @AuthenticationPrincipal JwtUserPrincipal principal) {
    return userService.updateProfile(userId, request, principal);
  }
}

