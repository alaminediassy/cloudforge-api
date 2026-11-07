package io.cloudforge.identity.application.port;

public interface EmailProvider {

    /**
     * Sends an activation code or throws NotificationDeliveryException.
     * A successful call does not prove delivery to the recipient's inbox.
     */
    void sendActivationEmail(String email, String otp);
}
