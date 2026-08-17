package com.okututor.backend.service;

import com.okututor.backend.dto.auth.AuthResponse;
import com.okututor.backend.dto.auth.LoginRequest;
import com.okututor.backend.dto.auth.RegisterRequest;
import com.okututor.backend.dto.user.UserProfileResponse;
import com.okututor.backend.entity.UserEntity;
import com.okututor.backend.exception.ApiBadRequestException;
import com.okututor.backend.exception.ApiNotFoundException;
import com.okututor.backend.exception.ApiUnauthorizedException;
import com.okututor.backend.repository.UserRepository;
import com.okututor.backend.security.JwtService;
import com.okututor.backend.security.JwtUserPrincipal;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;

  public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
  }

  public AuthResponse register(RegisterRequest request) {
    if (!request.password().equals(request.repeatPassword())) {
      throw new ApiBadRequestException("Passwords do not match");
    }

    userRepository.findByEmail(request.email()).ifPresent(user -> {
      throw new ApiBadRequestException("Email already exists");
    });

    UserEntity user = new UserEntity();
    user.setEmail(request.email());
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    user.setFullName(request.fullName());
    user.setRole("ROLE_USER");
    userRepository.save(user);

    String token = jwtService.generateToken(user);
    return new AuthResponse(token, UserProfileResponse.fromEntity(user));
  }

  public AuthResponse login(LoginRequest request) {
    UserEntity user = userRepository.findByEmail(request.email())
        .orElseThrow(() -> new ApiNotFoundException("User not found"));

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new ApiBadRequestException("Incorrect password");
    }

    String token = jwtService.generateToken(user);
    return new AuthResponse(token, UserProfileResponse.fromEntity(user));
  }

  public UserProfileResponse me(JwtUserPrincipal principal) {
    if (principal == null) {
      throw new ApiUnauthorizedException("Unauthorized");
    }
    UserEntity user = userRepository.findById(principal.getId())
        .orElseThrow(() -> new ApiNotFoundException("User not found"));
    return UserProfileResponse.fromEntity(user);
  }
}
