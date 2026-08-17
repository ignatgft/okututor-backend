package com.okututor.backend.exception;

public class ApiNotFoundException extends RuntimeException {
  public ApiNotFoundException(String message) {
    super(message);
  }
}

