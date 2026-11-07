package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.SendActivationOtpCommand;
import io.cloudforge.identity.application.port.*;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class SendActivationOtpUseCaseTest {
    @Mock UserRepository users;
    @Mock OtpGenerator generator;
    @Mock OtpStore store;
    @Mock EmailProvider email;
    @Mock HashProvider hash;
    SendActivationOtpUseCase useCase;
    final String address = "fixture@example.test";
    User user;

    @BeforeEach void setup() {
        useCase = new SendActivationOtpUseCase(new PrepareActivationOtpUseCase(users, generator, store, hash), email);
        user = User.create(UUID.randomUUID(), address, "First", "Last", "hash");
        when(users.findByEmailForUpdate(address)).thenReturn(Optional.of(user));
    }
    private void prepare(OtpStore.IssueResult result) {
        when(generator.generate()).thenReturn("123456");
        when(hash.hash("123456")).thenReturn("hashed");
        when(store.issue(eq(address), eq("hashed"), any(), any())).thenReturn(result);
    }
    @Test void storesBeforeSending() {
        prepare(OtpStore.IssueResult.STORED);
        useCase.send(new SendActivationOtpCommand(address));
        var order = inOrder(store, email);
        order.verify(store).issue(eq(address), eq("hashed"), eq(java.time.Duration.ofMinutes(7)), eq(java.time.Duration.ofMinutes(2)));
        order.verify(email).sendActivationEmail(address, "123456");
    }
    @Test void cooldownDoesNotSend() {
        prepare(OtpStore.IssueResult.COOLDOWN);
        assertThrows(OtpCooldownException.class, () -> useCase.send(new SendActivationOtpCommand(address)));
        verifyNoInteractions(email);
    }
    @Test void blockedDoesNotSend() {
        prepare(OtpStore.IssueResult.BLOCKED);
        assertThrows(VerificationBlockedException.class, () -> useCase.send(new SendActivationOtpCommand(address)));
        verifyNoInteractions(email);
    }
    @Test void redisFailureDoesNotSend() {
        when(generator.generate()).thenReturn("123456");
        when(hash.hash("123456")).thenReturn("hashed");
        when(store.issue(any(), any(), any(), any())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("fixture"));
        assertThrows(org.springframework.data.redis.RedisConnectionFailureException.class, () -> useCase.send(new SendActivationOtpCommand(address)));
        verifyNoInteractions(email);
    }
    @Test void notificationFailurePropagatesWithoutDeletingStoredCode() {
        prepare(OtpStore.IssueResult.STORED);
        doThrow(new NotificationDeliveryException()).when(email).sendActivationEmail(any(), any());
        assertThrows(NotificationDeliveryException.class, () -> useCase.send(new SendActivationOtpCommand(address)));
        verify(store).issue(any(), any(), any(), any());
        verifyNoMoreInteractions(store);
    }
    @Test void rejectsSuspendedBeforeExternalEffects() {
        user.suspend();
        assertThrows(InvalidUserStateException.class, () -> useCase.send(new SendActivationOtpCommand(address)));
        verifyNoInteractions(store, email, generator);
    }
    @Test void rejectsActiveBeforeExternalEffects() {
        user.activate();
        assertThrows(UserAlreadyActiveException.class, () -> useCase.send(new SendActivationOtpCommand(address)));
        verifyNoInteractions(store, email, generator);
    }
    @Test void rejectsUnknownUser() {
        when(users.findByEmailForUpdate(address)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> useCase.send(new SendActivationOtpCommand(address)));
        verifyNoInteractions(store, email, generator);
    }
}
