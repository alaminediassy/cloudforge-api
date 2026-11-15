package io.cloudforge.identity.infrastructure.persistence;

import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.UserStatus;
import io.cloudforge.identity.domain.exception.UserAlreadyExistsException;
import io.cloudforge.identity.infrastructure.persistence.adapter.UserRepositoryAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.SQLException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import io.cloudforge.identity.api.exception.GlobalExceptionHandler;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(UserRepositoryAdapter.class)
class UserRepositoryAdapterTest {
    @Autowired UserRepositoryAdapter repository;
    @Autowired JdbcTemplate jdbc;
    @Test void roundTripsSchemaBoundaries() {
        String email = "a".repeat(243) + "@example.org";
        User user = User.create(UUID.randomUUID(), email, "f".repeat(100), "l".repeat(100), "hash");
        repository.save(user);
        User restored = repository.findById(user.getId()).orElseThrow();
        assertEquals(email, restored.getEmail());
        assertEquals(UserStatus.PENDING, restored.getStatus());
        assertEquals(user.getFirstName(), restored.getFirstName());
    }
    @Test void translatesDatabaseEmailUniqueViolation() {
        String email = "adama.ndiaye+" + UUID.randomUUID() + "@example.test";
        repository.save(User.create(UUID.randomUUID(), email, "Adama", "Ndiaye", "hash"));
        assertThrows(UserAlreadyExistsException.class, () -> repository.save(User.create(UUID.randomUUID(), email, "Élodie", "Martin", "hash")));
    }
    @Test void doesNotMisclassifyOtherConstraintViolationsAsEmailConflicts() {
        assertThrows(DataIntegrityViolationException.class, () -> repository.save(User.create(UUID.randomUUID(), "adama.ndiaye+" + UUID.randomUUID() + "@example.test", null, "Ndiaye", "hash")));
    }
    @Test void realEmailConflictLogsOnlySafeMetadata(CapturedOutput output) {
        // No test-specific logging override: this comes from application.yml.
        assertEquals(Level.OFF, ((Logger) LoggerFactory.getLogger(
                "org.hibernate.engine.jdbc.spi.SqlExceptionHelper")).getEffectiveLevel());
        String email = "adama.ndiaye+private-email-" + UUID.randomUUID() + "@example.test";
        String privateName = "PRIVATE_NAME_SENTINEL";
        String privateHash = "PRIVATE_HASH_SENTINEL";
        repository.save(User.create(UUID.randomUUID(), email, privateName, "Ndiaye", privateHash));
        assertThrows(UserAlreadyExistsException.class, () -> repository.save(
                User.create(UUID.randomUUID(), email, privateName, "Ndiaye", privateHash)));
        assertTrue(output.getAll().contains("sqlState=23505, constraint=users_email_key"));
        assertFalse(output.getAll().contains(email));
        assertFalse(output.getAll().contains(privateName));
        assertFalse(output.getAll().contains(privateHash));
        assertFalse(output.getAll().contains("Key (email)="));
    }

    @Test void realPostgresLengthErrorKeepsSqlStateWithoutLeakingValues(CapturedOutput output) {
        String email = "adama.ndiaye+private-length-" + UUID.randomUUID() + "@example.test";
        String name = "x".repeat(101);
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> repository.save(User.create(UUID.randomUUID(), email, name, "Ndiaye", "PRIVATE_HASH_SENTINEL")));
        assertEquals("22001", sqlCause(error).getSQLState());
        var response = new GlobalExceptionHandler().handleUnexpectedException(error,
                new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/identity/register")));
        assertEquals(500, response.getStatusCode().value());
        assertTrue(output.getAll().contains("sqlState=22001"));
        assertTrue(output.getAll().contains("org.postgresql.util.PSQLException"));
        assertFalse(output.getAll().contains(email));
        assertFalse(output.getAll().contains(name));
        assertFalse(output.getAll().contains("PRIVATE_HASH_SENTINEL"));
        assertFalse(output.getAll().contains("value too long"));
    }

    @Test void anotherRealUniqueConstraintIsNotAnEmailConflict(CapturedOutput output) {
        // Transactional PostgreSQL DDL: index and fixture rows are rolled back after this test.
        // The partial index applies only to our synthetic fixture, not existing test data.
        jdbc.execute("CREATE UNIQUE INDEX users_group2_first_name_key ON users(first_name) "
                + "WHERE first_name = 'GROUP2_UNIQUE_NAME_SENTINEL'");
        String name = "GROUP2_UNIQUE_NAME_SENTINEL";
        String email = "adama.ndiaye+" + UUID.randomUUID() + "@example.test";
        repository.save(User.create(UUID.randomUUID(), "adama.ndiaye+" + UUID.randomUUID() + "@example.test", name, "Ndiaye", "hash"));
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> repository.save(User.create(UUID.randomUUID(), email, name, "Ndiaye", "hash")));
        assertEquals("23505", sqlCause(error).getSQLState());
        Throwable cause = error;
        while (!(cause instanceof org.hibernate.exception.ConstraintViolationException)) {
            cause = cause.getCause();
            assertNotNull(cause);
        }
        assertEquals("users_group2_first_name_key",
                ((org.hibernate.exception.ConstraintViolationException) cause).getConstraintName());
        new GlobalExceptionHandler().handleUnexpectedException(error,
                new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/identity/register")));
        assertTrue(output.getAll().contains("sqlState=23505"));
        assertFalse(output.getAll().contains(email));
        assertFalse(output.getAll().contains(name));
    }

    @Test
    void updatesOnlyNamesAndModificationTime() {
        UUID id = UUID.randomUUID();
        String email = "adama.ndiaye+" + id + "@example.test";

        User user = User.restore(
                id,
                email,
                "Adama",
                "Ndiaye",
                "original-password-hash",
                UserStatus.ACTIVE
        );
        repository.save(user);

        // A fixed old timestamp avoids timing-dependent assertions.
        jdbc.update("""
            UPDATE users
            SET updated_at = TIMESTAMPTZ '2000-01-01 00:00:00+00'
            WHERE id = ?
            """, id);

        var before = jdbc.queryForMap(
                "SELECT * FROM users WHERE id = ?",
                id
        );

        User locked = repository.findByIdForUpdate(id).orElseThrow();
        locked.updateNames("Élodie", "Martin");

        int updated = repository.updateNames(
                locked.getId(),
                locked.getFirstName(),
                locked.getLastName()
        );

        var after = jdbc.queryForMap(
                "SELECT * FROM users WHERE id = ?",
                id
        );

        assertAll(
                () -> assertEquals(1, updated),
                () -> assertEquals("Élodie", after.get("first_name")),
                () -> assertEquals("Martin", after.get("last_name")),
                () -> assertEquals(before.get("id"), after.get("id")),
                () -> assertEquals(before.get("email"), after.get("email")),
                () -> assertEquals(
                        before.get("password_hash"),
                        after.get("password_hash")
                ),
                () -> assertEquals(before.get("status"), after.get("status")),
                () -> assertEquals(before.get("created_at"), after.get("created_at")),
                () -> assertNotEquals(before.get("updated_at"), after.get("updated_at"))
        );

        User reloaded = repository.findById(id).orElseThrow();
        assertEquals("Élodie", reloaded.getFirstName());
        assertEquals("Martin", reloaded.getLastName());
    }

    @Test
    void returnsZeroWhenUpdatingMissingAccount() {
        UUID missingId = UUID.randomUUID();

        assertTrue(repository.findByIdForUpdate(missingId).isEmpty());

        int updated = repository.updateNames(missingId, "Adama", "Ndiaye");

        assertEquals(0, updated);
        assertTrue(repository.findById(missingId).isEmpty());
    }

    private SQLException sqlCause(Throwable error) {
        while (!(error instanceof SQLException)) {
            error = error.getCause();
            assertNotNull(error, "Expected a genuine JDBC exception from PostgreSQL");
        }
        return (SQLException) error;
    }
}
