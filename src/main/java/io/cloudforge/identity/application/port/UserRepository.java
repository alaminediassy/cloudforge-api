package io.cloudforge.identity.application.port;

import io.cloudforge.identity.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    Optional<User> findByEmail(String email);

    /**
     * Locks the account until the caller's transaction ends.
     * Requires a transaction to keep activation and code issuance in order.
     */
    Optional<User> findByEmailForUpdate(String email);

    void save(User user);

    Optional<User> findById(UUID id);
}