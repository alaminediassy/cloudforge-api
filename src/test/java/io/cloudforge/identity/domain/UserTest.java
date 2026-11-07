package io.cloudforge.identity.domain;

import io.cloudforge.identity.domain.exception.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class UserTest {
    private User pending() {
        return User.create(UUID.randomUUID(), "test@example.org", "First", "Last", "hash");
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
        User user = User.restore(UUID.randomUUID(), "test@example.org", "First", "Last", "hash", UserStatus.SUSPENDED);
        assertThrows(InvalidUserStateException.class, user::activate);
        assertFalse(user.isActive());
    }
    @Test void repeatedActivationRetainsExistingConflict() {
        User user = pending(); user.activate();
        assertThrows(UserAlreadyActiveException.class, user::activate);
        assertTrue(user.isActive());
    }
    @Test void rejectsMissingIdentityOrStatus() {
        assertThrows(NullPointerException.class, () -> User.create(null, "a@b.org", "First", "Last", "hash"));
        assertThrows(NullPointerException.class, () -> User.restore(UUID.randomUUID(), "a@b.org", "First", "Last", "hash", null));
    }
}
