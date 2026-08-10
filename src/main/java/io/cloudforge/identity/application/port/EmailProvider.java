package io.cloudforge.identity.application.port;

public interface EmailProvider {

    void sendActivationEmail(String email, String otp);
}
