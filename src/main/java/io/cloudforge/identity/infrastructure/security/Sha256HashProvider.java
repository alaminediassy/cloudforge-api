package io.cloudforge.identity.infrastructure.security;

import io.cloudforge.identity.application.port.HashProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class Sha256HashProvider implements HashProvider {

    private static final String ALGORITHM = "SHA-256";

    @Override
    public String hash(String value) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(ALGORITHM);

            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    e
            );
        }
    }
}