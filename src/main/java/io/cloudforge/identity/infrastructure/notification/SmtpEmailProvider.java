package io.cloudforge.identity.infrastructure.notification;

import io.cloudforge.identity.application.port.EmailProvider;
import io.cloudforge.identity.application.port.NotificationDeliveryException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Profile({"dev", "test"})
public class SmtpEmailProvider implements EmailProvider {
    private final JavaMailSender sender;

    public SmtpEmailProvider(JavaMailSender sender, @Value("${spring.mail.host}") String host) {
        // Only local email capture is supported. External SMTP is not configured.
        if (!Set.of("localhost", "127.0.0.1", "::1", "mailpit").contains(host)) {
            throw new IllegalArgumentException("Only the local mail capture service is supported");
        }
        this.sender = sender;
    }

    @Override
    public void sendActivationEmail(String email, String otp) {
        var message = new SimpleMailMessage();
        message.setFrom("no-reply@cloudforge.test");
        message.setTo(email);
        message.setSubject("Your CloudForge activation code");
        message.setText("Your activation code is " + otp + ". It expires seven minutes after creation.");
        try {
            sender.send(message);
        } catch (MailException ex) {
            // Mail errors may contain addresses and message text. Do not log or pass them on.
            throw new NotificationDeliveryException();
        }
    }
}
