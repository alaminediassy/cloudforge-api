package io.cloudforge.identity.application.port;

public interface HashProvider {

    /** Returns a stable hash for comparing stored codes and token references. */
    String hash(String value);
}