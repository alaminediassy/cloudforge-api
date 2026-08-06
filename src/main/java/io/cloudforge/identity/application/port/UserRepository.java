package io.cloudforge.identity.application.port;

import io.cloudforge.identity.domain.User;

import java.util.Optional;

public interface UserRepository {

    Optional<User> findByEmail(String email);

    void save(User user);

}