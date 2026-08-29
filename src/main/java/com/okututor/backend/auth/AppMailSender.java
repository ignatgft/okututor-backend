package com.okututor.backend.auth;

/**
 * абстракция отправки писем. Когда app.mail.enabled=false (локальная разработка),
 * коды пишутся в лог вместо отправки — весь флоу остаётся тестируемым.
 */
public interface AppMailSender {

    void sendVerificationCode(String to, String code, String purpose);

    void sendPlainText(String to, String subject, String body);
}
