package com.nomi.wayfinder.service;

import com.nomi.wayfinder.config.NomiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends transactional e-mails over SMTP (MAIL_HOST etc. in .env).
 * Sending runs on a virtual thread: a slow SMTP server does not block the request, and the
 * response time does not reveal whether the address belongs to an account.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final NomiProperties.Mail properties;
    private final boolean configured;

    public MailService(
            ObjectProvider<JavaMailSender> mailSender,
            NomiProperties properties,
            // Boot creates a sender even for an empty host, so check the host itself
            @Value("${spring.mail.host:}") String host
    ) {
        this.mailSender = mailSender;
        this.properties = properties.mail();
        this.configured = !host.isBlank();
    }

    public void sendPasswordResetCode(String email, String code, long validMinutes) {
        JavaMailSender sender = configured ? mailSender.getIfAvailable() : null;
        if (sender == null) {
            if (properties.devLogCodes()) {
                log.info("MAIL_HOST not set, password reset code for {}: {}", email, code);
            } else {
                log.error("Cannot send password reset e-mail: MAIL_HOST is not configured");
            }
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject("Nomi şifre sıfırlama kodun: " + code);
        message.setText("""
                Merhaba,

                Nomi hesabının şifresini sıfırlamak için kodun:

                %s

                Kod %d dakika geçerlidir. Bu isteği sen yapmadıysan bu e-postayı yok sayabilirsin; şifren değişmez.

                Nomi
                """.formatted(code, validMinutes));

        Thread.ofVirtual().start(() -> {
            try {
                sender.send(message);
            } catch (Exception e) {
                log.error("Password reset e-mail could not be sent: {}", e.getMessage());
            }
        });
    }
}
