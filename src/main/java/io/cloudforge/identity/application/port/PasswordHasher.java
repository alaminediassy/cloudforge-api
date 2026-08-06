package io.cloudforge.identity.application.port;

public interface PasswordHasher {

    String hash(String rawPassword);

}