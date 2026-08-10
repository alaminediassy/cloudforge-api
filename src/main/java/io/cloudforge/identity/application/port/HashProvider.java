package io.cloudforge.identity.application.port;

public interface HashProvider {

    String hash(String value);
}