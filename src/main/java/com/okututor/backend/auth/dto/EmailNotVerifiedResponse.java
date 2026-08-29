package com.okututor.backend.auth.dto;

/**
 * ответ HTTP 200 (не 401!), когда аккаунт есть, но адрес ещё не
 * не подтверждён — фронт проверяет data.status === "EMAIL_NOT_VERIFIED".
 */
public record EmailNotVerifiedResponse(
        String status,
        String email,
        String error
) {

    public static EmailNotVerifiedResponse of(String email) {
        return new EmailNotVerifiedResponse("EMAIL_NOT_VERIFIED", email, "EMAIL_NOT_VERIFIED");
    }
}
