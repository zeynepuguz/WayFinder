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
 * Sends transactional e-mails over SMTP (MAIL_HOST etc. in .env): the codes of sign-up, sign-in and password reset.
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
        send(email, code, "Nomi şifre sıfırlama kodun: " + code, """
                Merhaba,

                Nomi hesabının şifresini sıfırlamak için kodun:

                %s

                Kod %d dakika geçerlidir. Bu isteği sen yapmadıysan bu e-postayı yok sayabilirsin; şifren değişmez.

                Nomi
                """.formatted(code, validMinutes));
    }

    public void sendSignUpCode(String email, String firstName, String code, long validMinutes) {
        send(email, code, "Nomi doğrulama kodun: " + code, """
                Merhaba %s,

                Nomi hesabını oluşturmak için e-posta adresini doğrulama kodun:

                %s

                Kod %d dakika geçerlidir. Nomi'ye sen kaydolmadıysan bu e-postayı yok sayabilirsin.

                Nomi
                """.formatted(firstName, code, validMinutes));
    }

    public void sendSignInCode(String email, String code, long validMinutes) {
        send(email, code, "Nomi giriş kodun: " + code, """
                Merhaba,

                Nomi hesabına giriş yapmak için kodun:

                %s

                Kod %d dakika geçerlidir. Giriş yapmaya çalışan sen değilsen bu kodu kimseyle paylaşma ve
                şifreni değiştir: birisi şifreni biliyor, ama bu kod olmadan hesabına giremez.

                Nomi
                """.formatted(code, validMinutes));
    }

    private void send(String email, String code, String subject, String text) {
        JavaMailSender sender = configured ? mailSender.getIfAvailable() : null;
        if (sender == null) {
            if (properties.devLogCodes()) {
                log.info("MAIL_HOST not set, code for {}: {}", email, code);
            } else {
                log.error("Cannot send e-mail: MAIL_HOST is not configured");
            }
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject(subject);
        message.setText(text);

        Thread.ofVirtual().start(() -> {
            try {
                sender.send(message);
            } catch (Exception e) {
                log.error("E-mail could not be sent: {}", e.getMessage());
            }
        });
    }
}
