package io.cloudforge.shared.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class JwtPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesConfiguration { }

    @Test void invalidSigningKeyFailsConfigurationBindingWithoutDisclosure(CapturedOutput output) {
        String secret = "private-short-key-fixture";
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("cloudforge.security.jwt.secret=" + secret,
                        "cloudforge.security.jwt.access-token-expiration=15m",
                        "cloudforge.security.jwt.refresh-token-expiration=7d",
                        "cloudforge.security.jwt.issuer=cloudforge-api")
                .run(context -> assertNotNull(context.getStartupFailure()));
        assertFalse(output.getAll().contains(secret));
    }

    @Test void rejectsMissingIssuerAndNonpositiveDurations() {
        String secret = "s".repeat(32);
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(secret, Duration.ofMinutes(1), Duration.ofDays(1), " "));
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(secret, Duration.ZERO, Duration.ofDays(1), "issuer"));
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(secret, Duration.ofMinutes(1), Duration.ofSeconds(-1), "issuer"));
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(secret, null, Duration.ofDays(1), "issuer"));
    }

    @Test void measuresKeyBytesAndRedactsRecordRepresentation() {
        String secret = "é".repeat(16);
        var properties = new JwtProperties(secret, Duration.ofMinutes(1), Duration.ofDays(1), "issuer");
        assertFalse(properties.toString().contains(secret));
        assertTrue(properties.toString().contains("<redacted>"));
    }
}
