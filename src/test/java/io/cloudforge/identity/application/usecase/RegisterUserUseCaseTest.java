package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.RegisterUserCommand;
import io.cloudforge.identity.application.port.*;
import io.cloudforge.identity.domain.*;
import io.cloudforge.identity.domain.exception.UserAlreadyExistsException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegisterUserUseCaseTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final RegisterUserUseCase useCase = new RegisterUserUseCase(repository, hasher);
    private final RegisterUserCommand command = new RegisterUserCommand("register@example.org", "First", "Last", "test-password");
    @Test void persistsPendingUserWithHashOnly() {
        when(hasher.hash(command.password())).thenReturn("encoded-hash");
        User user = useCase.register(command);
        assertEquals(UserStatus.PENDING, user.getStatus());
        assertEquals("encoded-hash", user.getPasswordHash());
        assertEquals(command.email(), user.getEmail());
        assertNotNull(user.getId());
        verify(repository).save(user);
    }
    @Test void rejectsExistingEmailBeforeHashing() {
        when(repository.findByEmail(command.email())).thenReturn(Optional.of(User.create(UUID.randomUUID(), command.email(), "First", "Last", "hash")));
        assertThrows(UserAlreadyExistsException.class, () -> useCase.register(command));
        verifyNoInteractions(hasher);
        verify(repository, never()).save(any());
    }
    @Test void propagatesConcurrentEmailConflict() {
        when(hasher.hash(command.password())).thenReturn("hash");
        doThrow(new UserAlreadyExistsException(command.email())).when(repository).save(any());
        assertThrows(UserAlreadyExistsException.class, () -> useCase.register(command));
    }
}
