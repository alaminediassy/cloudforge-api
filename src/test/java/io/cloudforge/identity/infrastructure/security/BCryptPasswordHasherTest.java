package io.cloudforge.identity.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class BCryptPasswordHasherTest {
    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher(new BCryptPasswordEncoder(4));
    @Test void acceptsExactly72AsciiBytes() {
        String password = "a".repeat(72);
        String hash = hasher.hash(password);
        assertNotEquals(password, hash);
        assertTrue(hasher.matches(password, hash));
        assertFalse(hasher.matches(password + "b", hash));
    }
    @Test void respectsUtf8BoundaryWithoutTruncation() {
        String password = "é".repeat(36);
        String hash = hasher.hash(password);
        assertTrue(hasher.matches(password, hash));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(password + "x"));
        assertFalse(hasher.matches(password + "x", hash));
        assertFalse(hasher.matches(null, hash));
        assertFalse(hasher.matches("wrong", hash));
    }
    @Test void rejects73AsciiBytes() {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("a".repeat(73)));
    }
}
