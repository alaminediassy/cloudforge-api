package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.VerifyEmailCommand;
import io.cloudforge.identity.application.port.*;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class VerifyEmailUseCaseTest {
    @Mock UserRepository users;
    @Mock OtpStore store;
    @Mock TokenProvider tokens;
    @Mock RefreshTokenStore refresh;
    @Mock HashProvider hash;
    VerifyEmailUseCase useCase;
    User user;
    final String address = "fixture@example.test";
    final VerifyEmailCommand command = new VerifyEmailCommand(address, "123456");
    @BeforeEach void setup() {
        useCase = new VerifyEmailUseCase(users, store, tokens, refresh, hash);
        user = User.create(UUID.randomUUID(), address, "First", "Last", "hash");
        when(users.findByEmailForUpdate(address)).thenReturn(Optional.of(user));
    }
    private void outcome(OtpStore.VerificationResult result) {
        when(hash.hash("123456")).thenReturn("hashed");
        when(store.verifyAndConsume(eq(address), eq("hashed"), eq(3), any(), any())).thenReturn(result);
    }
    @Test void consumesBeforeActivationAndTokenStorage() {
        outcome(OtpStore.VerificationResult.ACCEPTED);
        when(tokens.generateAccessToken(user)).thenReturn("access");
        when(tokens.generateRefreshToken(user)).thenReturn("refresh");
        when(hash.hash("refresh")).thenReturn("refresh-hash");
        when(tokens.getRefreshTokenTtl()).thenReturn(Duration.ofDays(7));
        var result = useCase.verify(command);
        assertTrue(user.isActive());
        assertEquals("access", result.accessToken());
        assertEquals("refresh", result.refreshToken());
        var order = inOrder(store, users, refresh);
        order.verify(users).findByEmailForUpdate(address);
        order.verify(store).verifyAndConsume(address, "hashed", 3, Duration.ofMinutes(7), Duration.ofMinutes(15));
        order.verify(users).save(user);
        order.verify(refresh).save("refresh-hash", user.getId(), Duration.ofDays(7));
    }
    @Test void invalidNeverActivates() {
        outcome(OtpStore.VerificationResult.INVALID);
        assertThrows(InvalidOtpException.class, () -> useCase.verify(command));
        assertFalse(user.isActive());
        verify(users, never()).save(any());
        verifyNoInteractions(tokens, refresh);
    }
    @Test void blockedNeverActivates() {
        outcome(OtpStore.VerificationResult.BLOCKED);
        assertThrows(VerificationBlockedException.class, () -> useCase.verify(command));
        assertFalse(user.isActive());
        verifyNoInteractions(tokens, refresh);
    }
    @Test void suspendedCannotConsumeCode() {
        user.suspend();
        assertThrows(InvalidUserStateException.class, () -> useCase.verify(command));
        verifyNoInteractions(store, tokens, refresh);
    }
    @Test void activeCannotConsumeCode() {
        user.activate();
        assertThrows(UserAlreadyActiveException.class, () -> useCase.verify(command));
        verifyNoInteractions(store, tokens, refresh);
    }
    @Test void missingUserCannotConsumeCode() {
        when(users.findByEmailForUpdate(address)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> useCase.verify(command));
        verifyNoInteractions(store, tokens, refresh);
    }
    @Test void storageFailureCannotActivate() {
        when(hash.hash("123456")).thenReturn("hashed");
        when(store.verifyAndConsume(any(), any(), anyInt(), any(), any())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("fixture"));
        assertThrows(org.springframework.data.redis.RedisConnectionFailureException.class, () -> useCase.verify(command));
        assertFalse(user.isActive());
        verify(users, never()).save(any());
        verifyNoInteractions(tokens, refresh);
    }
}
