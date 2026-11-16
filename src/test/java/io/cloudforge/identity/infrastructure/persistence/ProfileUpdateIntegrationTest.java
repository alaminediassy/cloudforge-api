package io.cloudforge.identity.infrastructure.persistence;

import io.cloudforge.identity.application.command.UpdateMyProfileCommand;
import io.cloudforge.identity.application.usecase.UpdateMyProfileUseCase;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.UserStatus;
import io.cloudforge.identity.infrastructure.persistence.adapter.UserRepositoryAdapter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({UserRepositoryAdapter.class, UpdateMyProfileUseCase.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProfileUpdateIntegrationTest {
    @Autowired UpdateMyProfileUseCase useCase;
    @MockitoSpyBean UserRepositoryAdapter repository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    private UUID id;

    @BeforeEach
    void createOwnedAccount() {
        id = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> repository.save(
                User.restore(id, "adama.ndiaye+" + id + "@example.test",
                        "Adama", "Ndiaye", "test-hash", UserStatus.ACTIVE)));
    }

    @AfterEach
    void removeOwnedAccount() {
        jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    @Test
    void rollsBackWhenFailureOccursAfterSqlUpdate() {
        var before = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);
        doAnswer(call -> {
            assertEquals(1, call.callRealMethod());
            throw new IllegalStateException("Simulated failure after SQL update");
        }).when(repository).updateNames(eq(id), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> useCase.updateProfile(id,
                new UpdateMyProfileCommand("Élodie", "Martin")));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id));
    }

    @Test
    void concurrentPartialUpdatesKeepBothChanges() throws Exception {
        var started = new CountDownLatch(1);
        var pid = new java.util.concurrent.atomic.AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                useCase.updateProfile(id, new UpdateMyProfileCommand("Élodie", null));
                Future<?> second = executor.submit(() -> new TransactionTemplate(transactions)
                        .executeWithoutResult(other -> {
                            jdbc.execute("SET LOCAL lock_timeout = '8s'");
                            pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            started.countDown();
                            useCase.updateProfile(id, new UpdateMyProfileCommand(null, "Martin"));
                        }));
                pending = second;
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    // Observe a real PostgreSQL lock wait before committing the first update.
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    boolean blocked = false;
                    while (System.nanoTime() < deadline) {
                        blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid.get()));
                        if (blocked) break;
                        Thread.sleep(20);
                    }
                    assertTrue(blocked, "Second update must wait for the account lock");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            pending.get(10, TimeUnit.SECONDS);
            var user = repository.findById(id).orElseThrow();
            assertEquals("Élodie", user.getFirstName());
            assertEquals("Martin", user.getLastName());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
    private Future<?> pending;
}
