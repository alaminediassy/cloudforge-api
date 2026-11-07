package io.cloudforge.identity.application.port;

public interface PasswordHasher {

    /** Hashes a password without silently truncating it. Rejects unsupported lengths. */
    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);

}