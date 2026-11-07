package io.cloudforge.identity.infrastructure.notification;

import io.cloudforge.identity.application.port.EmailProvider;
import io.cloudforge.identity.application.port.NotificationDeliveryException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Refuses delivery when no local capture profile is active. */
@Component
@Profile("!dev & !test")
public class UnavailableEmailProvider implements EmailProvider {
    @Override
    public void sendActivationEmail(String email, String otp) {
        throw new NotificationDeliveryException();
    }
}
