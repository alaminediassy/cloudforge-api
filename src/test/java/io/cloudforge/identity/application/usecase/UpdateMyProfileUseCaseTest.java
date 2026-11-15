package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.UpdateMyProfileCommand;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.UserStatus;
import io.cloudforge.identity.domain.exception.AccountAccessDeniedException;
import io.cloudforge.identity.domain.exception.EmptyProfileUpdateException;
import io.cloudforge.identity.domain.exception.InvalidProfileException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpdateMyProfileUseCaseTest {

    @Mock
    private UserRepository users;

    private UpdateMyProfileUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateMyProfileUseCase(users);
    }

    @ParameterizedTest
    @MethodSource("validChanges")
    void updatesProvidedNamesAndKeepsMissingFields(
            String firstName,
            String lastName,
            String expectedFirstName,
            String expectedLastName
    ) {
        User user = account(UserStatus.ACTIVE);

        when(users.findByIdForUpdate(user.getId()))
                .thenReturn(Optional.of(user));
        when(users.updateNames(
                user.getId(), expectedFirstName, expectedLastName
        )).thenReturn(1);

        var result = useCase.updateProfile(
                user.getId(),
                new UpdateMyProfileCommand(firstName, lastName)
        );

        assertEquals(
                new UpdateMyProfileUseCase.UpdateProfileResult(
                        user.getId(),
                        user.getEmail(),
                        expectedFirstName,
                        expectedLastName
                ),
                result
        );
        assertEquals("original-hash", user.getPasswordHash());
        assertEquals(UserStatus.ACTIVE, user.getStatus());

        var order = inOrder(users);
        order.verify(users).findByIdForUpdate(user.getId());
        order.verify(users).updateNames(
                user.getId(), expectedFirstName, expectedLastName
        );
        verifyNoMoreInteractions(users);
    }

    @Test
    void rejectsEmptyUpdateBeforeReadingAccount() {
        assertThrows(
                EmptyProfileUpdateException.class,
                () -> useCase.updateProfile(
                        UUID.randomUUID(),
                        new UpdateMyProfileCommand(null, null)
                )
        );

        verifyNoInteractions(users);
    }

    @Test
    void rejectsMissingAccountWithoutWriting() {
        UUID id = UUID.randomUUID();
        when(users.findByIdForUpdate(id)).thenReturn(Optional.empty());

        assertThrows(
                AccountAccessDeniedException.class,
                () -> useCase.updateProfile(
                        id,
                        new UpdateMyProfileCommand("Élodie", null)
                )
        );

        verify(users).findByIdForUpdate(id);
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest
    @EnumSource(
            value = UserStatus.class,
            names = {"PENDING", "SUSPENDED"}
    )
    void rejectsInactiveAccountWithoutWriting(UserStatus status) {
        User user = account(status);
        when(users.findByIdForUpdate(user.getId()))
                .thenReturn(Optional.of(user));

        assertThrows(
                AccountAccessDeniedException.class,
                () -> useCase.updateProfile(
                        user.getId(),
                        new UpdateMyProfileCommand("Élodie", null)
                )
        );

        verify(users).findByIdForUpdate(user.getId());
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest
    @MethodSource("invalidChanges")
    void rejectsInvalidNamesWithoutWriting(
            String firstName,
            String lastName
    ) {
        User user = account(UserStatus.ACTIVE);
        when(users.findByIdForUpdate(user.getId()))
                .thenReturn(Optional.of(user));

        assertThrows(
                InvalidProfileException.class,
                () -> useCase.updateProfile(
                        user.getId(),
                        new UpdateMyProfileCommand(firstName, lastName)
                )
        );

        assertEquals("Adama", user.getFirstName());
        assertEquals("Ndiaye", user.getLastName());

        verify(users).findByIdForUpdate(user.getId());
        verifyNoMoreInteractions(users);
    }

    @Test
    void rejectsUnexpectedUpdateCount() {
        User user = account(UserStatus.ACTIVE);
        when(users.findByIdForUpdate(user.getId()))
                .thenReturn(Optional.of(user));
        when(users.updateNames(user.getId(), "Élodie", "Ndiaye"))
                .thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> useCase.updateProfile(
                        user.getId(),
                        new UpdateMyProfileCommand("Élodie", null)
                )
        );

        verify(users).findByIdForUpdate(user.getId());
        verify(users).updateNames(user.getId(), "Élodie", "Ndiaye");
        verifyNoMoreInteractions(users);
    }

    private static Stream<Arguments> validChanges() {
        return Stream.of(
                Arguments.of("Élodie", null, "Élodie", "Ndiaye"),
                Arguments.of(null, "Martin", "Adama", "Martin"),
                Arguments.of("Élodie", "Martin", "Élodie", "Martin")
        );
    }

    private static Stream<Arguments> invalidChanges() {
        return Stream.of(
                Arguments.of("", "Martin"),
                Arguments.of("Élodie", "   "),
                Arguments.of("a".repeat(101), null),
                Arguments.of(null, "a".repeat(101))
        );
    }

    private User account(UserStatus status) {
        return User.restore(
                UUID.randomUUID(),
                "adama.ndiaye@example.test",
                "Adama",
                "Ndiaye",
                "original-hash",
                status
        );
    }
}