package io.cloudforge.identity.application.port;

import io.cloudforge.identity.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    Optional<User> findByEmail(String email);

    void save(User user);

    Optional<User> findById(UUID id);
}