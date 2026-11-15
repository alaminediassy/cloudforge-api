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

    /**
     * Locks the account until the caller's transaction ends.
     *
     * <p>The caller must keep the read and update in the same transaction.
     */
    Optional<User> findByIdForUpdate(UUID id);

    /**
     * Updates only the names and modification time.
     *
     * <p>The caller must validate the names and lock the account with
     * findByIdForUpdate in the same transaction.
     *
     * @return the number of updated accounts: zero or one
     */
    int updateNames(UUID id, String firstName, String lastName);
}