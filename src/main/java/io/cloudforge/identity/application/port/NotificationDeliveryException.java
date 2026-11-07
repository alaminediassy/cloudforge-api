package io.cloudforge.identity.application.port;

/** Delivery failed or could not be confirmed. Contains no address or message text. */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException() {
        super("Notification delivery unavailable");
    }
}
