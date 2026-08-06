package io.cloudforge.identity.infrastructure.persistence.adapter;

import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.infrastructure.persistence.mapper.UserMapper;
import io.cloudforge.identity.infrastructure.persistence.repository.JpaUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

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
    public void save(User user) {

        jpaUserRepository.save(
                UserMapper.toEntity(user)
        );
    }

}