package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.UserStatus;
import io.cloudforge.identity.domain.exception.AccountAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetMyProfileUseCaseTest {

    @Mock
    private UserRepository userRepository;

    private GetMyProfileUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetMyProfileUseCase(userRepository);
    }

    @Test
    void shouldReturnActiveUserProfile() {
        UUID userId = UUID.randomUUID();
        User user = User.restore(
                userId,
                "adama@cloudforge.com",
                "Adama",
                "Ndiaye",
                "unused-password-hash",
                UserStatus.ACTIVE
        );

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        var result = useCase.getProfile(userId);

        assertEquals(
                new GetMyProfileUseCase.ProfileResult(
                        userId,
                        "adama@cloudforge.com",
                        "Adama",
                        "Ndiaye"
                ),
                result
        );

        verify(userRepository).findById(userId);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void shouldRejectMissingUser() {
        UUID userId = UUID.randomUUID();

        when(userRepository.findById(userId))
                .thenReturn(Optional.empty());

        assertThrows(
                AccountAccessDeniedException.class,
                () -> useCase.getProfile(userId)
        );

        verify(userRepository).findById(userId);
        verifyNoMoreInteractions(userRepository);
    }

    @ParameterizedTest
    @EnumSource(
            value = UserStatus.class,
            names = {"PENDING", "SUSPENDED"}
    )
    void shouldRejectInactiveUser(UserStatus status) {
        UUID userId = UUID.randomUUID();
        User user = User.restore(
                userId,
                "adama@cloudforge.com",
                "Adama",
                "Ndiaye",
                "unused-password-hash",
                status
        );

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        assertThrows(
                AccountAccessDeniedException.class,
                () -> useCase.getProfile(userId)
        );

        verify(userRepository).findById(userId);
        verifyNoMoreInteractions(userRepository);
    }
}
