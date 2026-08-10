package io.cloudforge.identity.infrastructure.notification;

import io.cloudforge.identity.application.port.EmailProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LogEmailProvider implements EmailProvider {

    @Override
    public void sendActivationEmail(String email, String otp) {
        log.info("======================================================");
        log.info("EMAIL SENT TO  : {}", email);
        log.info("SUBJECT        : Your CloudForge activation code");
        log.info("OTP CODE       : {}", otp);
        log.info("======================================================");

    }
}
