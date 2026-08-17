package com.okututor.backend.exception;

public class ApiUnauthorizedException extends RuntimeException {
  public ApiUnauthorizedException(String message) {
    super(message);
  }
}

