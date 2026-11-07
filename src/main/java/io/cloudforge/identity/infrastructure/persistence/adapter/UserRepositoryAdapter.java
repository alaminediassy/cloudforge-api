package io.cloudforge.identity.infrastructure.persistence.adapter;

import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.infrastructure.persistence.mapper.UserMapper;
import io.cloudforge.identity.infrastructure.persistence.repository.JpaUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import io.cloudforge.identity.domain.exception.UserAlreadyExistsException;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepository {

    private final JpaUserRepository jpaUserRepository;

    @Override
    public Optional<User> findByEmail(String email) {

        return jpaUserRepository.findByEmail(email)
                .map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmailForUpdate(String email) {
        return jpaUserRepository.findByEmailForUpdate(email).map(UserMapper::toDomain);
    }

    @Override
    public void save(User user) {

        try {
            // Flush here so a concurrent email conflict is translated before transaction commit.
            jpaUserRepository.saveAndFlush(UserMapper.toEntity(user));
        } catch (DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && "23505".equals(constraint.getSQLState())
                        && "users_email_key".equals(constraint.getConstraintName())) {
                    log.warn("User persistence conflict: sqlState=23505, constraint=users_email_key");
                    throw new UserAlreadyExistsException(user.getEmail());
                }
            }
            throw ex;
        }
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpaUserRepository.findById(id)
                .map(UserMapper::toDomain);
    }

}