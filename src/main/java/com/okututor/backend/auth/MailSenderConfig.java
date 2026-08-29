package com.okututor.backend.auth;

import com.okututor.backend.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
class MailSenderConfig {

    private static final Logger log = LoggerFactory.getLogger(MailSenderConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "true")
    AppMailSender smtpMailSender(JavaMailSender mailSender, AppProperties properties) {
        return new AppMailSender() {
            @Override
            public void sendVerificationCode(String to, String code, String purpose) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(properties.getMail().getFrom());
                message.setTo(to);
                message.setSubject("Okututor confirmation code: " + code);
                message.setText("""
                        Your Okututor %s code is: %s
                        The code expires in 10 minutes.
                        """.formatted(purposeLabel(purpose), code));
                mailSender.send(message);
            }

            @Override
            public void sendPlainText(String to, String subject, String body) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(properties.getMail().getFrom());
                message.setTo(to);
                message.setSubject(subject);
                message.setText(body);
                mailSender.send(message);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "false", matchIfMissing = true)
    AppMailSender loggingMailSender() {
        return new AppMailSender() {
            @Override
            public void sendVerificationCode(String to, String code, String purpose) {
                log.info("[DEV MAIL] {} code for {}: {}", purposeLabel(purpose), to, code);
            }

            @Override
            public void sendPlainText(String to, String subject, String body) {
                log.info("[DEV MAIL] to={} subject={} body={}", to, subject, body);
            }
        };
    }

    private static String purposeLabel(String purpose) {
        return switch (purpose) {
            case "PASSWORD_RESET" -> "password reset";
            case "EMAIL_CHANGE" -> "email change";
            default -> "verification";
        };
    }
}
