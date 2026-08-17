package com.okututor.backend.controller;

import com.okututor.backend.dto.auth.AuthResponse;
import com.okututor.backend.dto.auth.LoginRequest;
import com.okututor.backend.dto.auth.RegisterRequest;
import com.okututor.backend.dto.user.UserProfileResponse;
import com.okututor.backend.security.JwtUserPrincipal;
import com.okututor.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  public AuthResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request);
  }

  @PostMapping("/register")
  public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
    return authService.register(request);
  }

  @GetMapping("/me")
  public UserProfileResponse me(@AuthenticationPrincipal JwtUserPrincipal principal) {
    return authService.me(principal);
  }

  @GetMapping(value = "/auth/google", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> google() {
    return ResponseEntity.ok("""
        <html>
          <body style="font-family: sans-serif; padding: 24px;">
            <h2>Google OAuth is not wired in the mock backend yet.</h2>
            <p>This endpoint exists so the frontend button has a target.</p>
          </body>
        </html>
        """);
  }
}

