package io.cloudforge.identity.domain;

import io.cloudforge.identity.domain.exception.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

class UserTest {
    private User pending() {
        return User.create(UUID.randomUUID(), "adama.ndiaye@example.test", "Adama", "Ndiaye", "hash");
    }
    @Test void activatesPendingUser() {
        User user = pending();
        assertEquals(UserStatus.PENDING, user.getStatus());
        user.activate();
        assertTrue(user.isActive());
    }
    @Test void cannotActivateSuspendedUser() {
        User user = pending();
        user.activate();
        user.suspend();
        assertThrows(InvalidUserStateException.class, user::activate);
        assertEquals(UserStatus.SUSPENDED, user.getStatus());
    }
    @Test void cannotActivateRestoredSuspendedUser() {
        User user = User.restore(UUID.randomUUID(), "adama.ndiaye@example.test", "Adama", "Ndiaye", "hash", UserStatus.SUSPENDED);
        assertThrows(InvalidUserStateException.class, user::activate);
        assertFalse(user.isActive());
    }
    @Test void repeatedActivationRetainsExistingConflict() {
        User user = pending(); user.activate();
        assertThrows(UserAlreadyActiveException.class, user::activate);
        assertTrue(user.isActive());
    }
    @Test void rejectsMissingIdentityOrStatus() {
        assertThrows(NullPointerException.class, () -> User.create(null, "adama.ndiaye@example.test", "Adama", "Ndiaye", "hash"));
        assertThrows(NullPointerException.class, () -> User.restore(UUID.randomUUID(), "adama.ndiaye@example.test", "Adama", "Ndiaye", "hash", null));
    }
    @Test
    void updatesNamesWithoutChangingOtherAccountFields() {
        User user = pending();
        user.activate();
        UUID originalId = user.getId();

        user.updateNames("Élodie", "Martin");

        assertAll(
                () -> assertEquals("Élodie", user.getFirstName()),
                () -> assertEquals("Martin", user.getLastName()),
                () -> assertEquals(originalId, user.getId()),
                () -> assertEquals("adama.ndiaye@example.test", user.getEmail()),
                () -> assertEquals("hash", user.getPasswordHash()),
                () -> assertEquals(UserStatus.ACTIVE, user.getStatus())
        );
    }

    @Test
    void acceptsNamesAtLengthLimits() {
        User user = pending();
        String longestName = "a".repeat(100);

        user.updateNames("A", longestName);

        assertEquals("A", user.getFirstName());
        assertEquals(longestName, user.getLastName());

        user.updateNames(longestName, "B");

        assertEquals(longestName, user.getFirstName());
        assertEquals("B", user.getLastName());
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsInvalidFirstNameWithoutChangingEitherName(String invalidName) {
        User user = pending();

        assertThrows(
                InvalidProfileException.class,
                () -> user.updateNames(invalidName, "Ndiaye")
        );

        assertAll(
                () -> assertEquals("Adama", user.getFirstName()),
                () -> assertEquals("Ndiaye", user.getLastName())
        );
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsInvalidLastNameWithoutChangingEitherName(String invalidName) {
        User user = pending();

        assertThrows(
                InvalidProfileException.class,
                () -> user.updateNames("Adama", invalidName)
        );

        assertAll(
                () -> assertEquals("Adama", user.getFirstName()),
                () -> assertEquals("Ndiaye", user.getLastName())
        );
    }

    private static Stream<String> invalidNames() {
        return Stream.of(
                (String) null,
                "",
                "   ",
                "\t\n",
                "a".repeat(101)
        );
    }
}
