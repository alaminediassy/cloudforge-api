package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.RegisterUserCommand;
import io.cloudforge.identity.application.port.PasswordHasher;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.UserAlreadyExistsException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class RegisterUserUseCase {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    public User register(RegisterUserCommand command) {

        userRepository.findByEmail(command.email())
                .ifPresent(user -> {
                    throw new UserAlreadyExistsException(command.email());
                });

        String passwordHash = passwordHasher.hash(command.password());

        User user = User.create(
                UUID.randomUUID(),
                command.email(),
                command.firstName(),
                command.lastName(),
                passwordHash
        );

        userRepository.save(user);

        return user;
    }

}