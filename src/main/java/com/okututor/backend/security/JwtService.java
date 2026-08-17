package com.okututor.backend.security;

import com.okututor.backend.entity.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private final SecretKey key;
  private final long expirationMillis;

  public JwtService(
      @Value("${app.jwt.secret:okututor-local-development-secret-key-change-me-1234567890}") String secret,
      @Value("${app.jwt.expiration-millis:604800000}") long expirationMillis) {
    this.key = Keys.hmacShaKeyFor(normalizeSecret(secret));
    this.expirationMillis = expirationMillis;
  }

  private byte[] normalizeSecret(String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length >= 32) {
      return bytes;
    }
    byte[] padded = new byte[32];
    for (int i = 0; i < padded.length; i++) {
      padded[i] = bytes[i % bytes.length];
    }
    return padded;
  }

  public String generateToken(UserEntity user) {
    Instant now = Instant.now();
    return Jwts.builder()
        .setSubject(user.getId())
        .claim("email", user.getEmail())
        .claim("role", user.getRole())
        .setIssuedAt(Date.from(now))
        .setExpiration(Date.from(now.plusMillis(expirationMillis)))
        .signWith(key, SignatureAlgorithm.HS256)
        .compact();
  }

  public Optional<String> extractUserId(String token) {
    try {
      Claims claims = Jwts.parserBuilder()
          .setSigningKey(key)
          .build()
          .parseClaimsJws(token)
          .getBody();
      return Optional.ofNullable(claims.getSubject());
    } catch (Exception ex) {
      return Optional.empty();
    }
  }
}
