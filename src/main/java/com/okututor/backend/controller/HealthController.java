package com.okututor.backend.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

  @GetMapping("/")
  public Map<String, String> root() {
    return Map.of("status", "ok", "service", "okututor-backend");
  }

  @GetMapping("/actuator/health")
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }
}

