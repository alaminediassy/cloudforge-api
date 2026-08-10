package io.cloudforge.identity.infrastructure.security;

import io.cloudforge.identity.application.port.OtpGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class SecureRandomOtpGenerator implements OtpGenerator {

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate() {
        int randomCode = secureRandom.nextInt(1000000);
        return String.format("%06d", randomCode);
    }
}
