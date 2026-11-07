package io.cloudforge.identity.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudforge.identity.application.command.*;
import io.cloudforge.identity.application.port.*;
import io.cloudforge.identity.application.usecase.*;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.UserStatus;
import io.cloudforge.identity.domain.exception.*;
import io.cloudforge.identity.infrastructure.persistence.redis.RedisOtpStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class OtpRecoveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RegisterUserUseCase registration;
    @Autowired SendActivationOtpUseCase send;
    @Autowired VerifyEmailUseCase verify;
    @Autowired RefreshSessionUseCase refresh;
    @Autowired LogoutUseCase logout;
    @Autowired HashProvider hashes;
    @Autowired TokenProvider tokens;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean RedisOtpStore store;
    @MockitoSpyBean RefreshTokenStore refreshStore;
    @MockitoBean EmailProvider email;
    @MockitoSpyBean OtpGenerator generator;
    final Set<String> fixtures = new HashSet<>();
    static final Duration OTP_TTL = Duration.ofMinutes(7);
    static final Duration COOLDOWN = Duration.ofMinutes(2);

    @BeforeEach void guardDedicatedResources() {
        assertEquals("cloudforge_test", jdbc.queryForObject("select current_database()", String.class));
        var factory = (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) redis.getConnectionFactory();
        assertNotNull(factory);
        assertEquals(6390, factory.getPort());
        assertEquals(1, factory.getDatabase());
    }
    String address() {
        String address = "g4-" + UUID.randomUUID() + "@example.test";
        fixtures.add(address);
        return address;
    }
    User pending(String address) {
        return registration.register(new RegisterUserCommand(address, "Fixture", "Only", "test-password"));
    }
    String key(String kind, String address) { return "cloudforge:otp:" + kind + ":" + address; }
    String hash(String address) { return redis.opsForValue().get(key("hash", address)); }
    UserStatus accountStatus(String address) { return users.findByEmail(address).orElseThrow().getStatus(); }
    void issue(String address, String code, Duration ttl, Duration cooldown) {
        assertEquals(OtpStore.IssueResult.STORED, store.issue(address, hashes.hash(code), ttl, cooldown));
    }
    OtpStore.VerificationResult consume(String address, String code) {
        return store.verifyAndConsume(address, hashes.hash(code), 3, OTP_TTL, Duration.ofMinutes(15));
    }
    void expireCooldown(String address) throws Exception {
        redis.expire(key("cooldown", address), Duration.ofMillis(30));
        awaitExpired(key("cooldown", address));
    }
    void awaitExpired(String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
    }
    String body(String address) throws Exception {
        return json.writeValueAsString(Map.of("email", address, "firstName", "Fixture", "lastName", "Only", "password", "test-password"));
    }
    Set<String> refreshKeys(String address) {
        var id = users.findByEmail(address).map(User::getId).map(UUID::toString).orElse("");
        Set<String> found = new HashSet<>();
        // Read only this namespace on the dedicated test instance; delete only values owned by our fixture.
        try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match("cloudforge:refresh-token:*").count(100).build())) {
            while (cursor.hasNext()) {
                String k = cursor.next();
                if (id.equals(redis.opsForValue().get(k))) found.add(k);
            }
        }
        return found;
    }
    @AfterEach void removeOnlyOwnedFixtures() {
        for (String address : fixtures) {
            Set<String> keys = refreshKeys(address);
            for (String kind : List.of("hash", "cooldown", "attempts", "blocked")) keys.add(key(kind, address));
            redis.delete(keys);
            jdbc.update("DELETE FROM users WHERE email = ?", address);
        }
    }
    @Test void concurrentIssuanceStoresExactlyOneCodeAndPreservesPendingAccount() throws Exception {
        String address = address(); pending(address);
        List<String> outcomes = race(8, () -> store.issue(address, hashes.hash(UUID.randomUUID().toString()), OTP_TTL, COOLDOWN).name());
        assertEquals(1, Collections.frequency(outcomes, "STORED"));
        assertEquals(7, Collections.frequency(outcomes, "COOLDOWN"));
        assertNotNull(hash(address));
        assertTrue(redis.getExpire(key("hash", address)) > 400);
        assertTrue(redis.getExpire(key("cooldown", address)) > 100);
        assertEquals(UserStatus.PENDING, accountStatus(address));
    }
    @Test void concurrentConsumptionAcceptsExactlyOnce() throws Exception {
        String address = address(); pending(address); issue(address, "123456", OTP_TTL, COOLDOWN);
        List<String> outcomes = race(8, () -> consume(address, "123456").name());
        assertEquals(1, Collections.frequency(outcomes, "ACCEPTED"));
        assertEquals(7, Collections.frequency(outcomes, "INVALID"));
        assertNull(hash(address));
        assertEquals(UserStatus.PENDING, accountStatus(address));
    }
    @Test void concurrentFailuresCannotExceedAttemptBudget() throws Exception {
        String address = address(); pending(address); issue(address, "123456", OTP_TTL, COOLDOWN);
        List<String> outcomes = race(8, () -> consume(address, "999999").name());
        assertEquals(2, Collections.frequency(outcomes, "INVALID"));
        assertEquals(6, Collections.frequency(outcomes, "BLOCKED"));
        assertEquals("3", redis.opsForValue().get(key("attempts", address)));
        assertTrue(redis.getExpire(key("attempts", address)) > 0);
        assertTrue(redis.getExpire(key("blocked", address)) > 880);
        assertNull(hash(address));
        assertEquals(OtpStore.VerificationResult.BLOCKED, consume(address, "123456"));
        assertEquals(OtpStore.IssueResult.BLOCKED, store.issue(address, "new", OTP_TTL, COOLDOWN));
        assertEquals(UserStatus.PENDING, accountStatus(address));
    }
    @Test void resendingDoesNotResetAttempts() throws Exception {
        String address = address(); pending(address); issue(address, "123456", OTP_TTL, COOLDOWN);
        assertEquals(OtpStore.VerificationResult.INVALID, consume(address, "999999"));
        assertEquals(OtpStore.VerificationResult.INVALID, consume(address, "999999"));
        Long ttl = redis.getExpire(key("attempts", address), TimeUnit.MILLISECONDS);
        expireCooldown(address);
        issue(address, "654321", OTP_TTL, COOLDOWN);
        assertEquals("2", redis.opsForValue().get(key("attempts", address)));
        assertTrue(redis.getExpire(key("attempts", address), TimeUnit.MILLISECONDS) <= ttl);
        assertEquals(OtpStore.VerificationResult.BLOCKED, consume(address, "999999"));
        assertEquals(UserStatus.PENDING, accountStatus(address));
    }
    @Test void expiryAndTemporaryBlockPermitRecovery() throws Exception {
        String address = address(); pending(address); issue(address, "123456", Duration.ofMillis(50), Duration.ofMillis(30));
        awaitExpired(key("hash", address));
        assertEquals(OtpStore.VerificationResult.INVALID, consume(address, "123456"));
        issue(address, "654321", OTP_TTL, Duration.ofMillis(30));
        assertEquals(OtpStore.VerificationResult.BLOCKED, store.verifyAndConsume(address, "wrong", 1,
                Duration.ofMillis(50), Duration.ofMillis(80)));
        assertEquals(UserStatus.PENDING, accountStatus(address));
        awaitExpired(key("blocked", address));
        awaitExpired(key("attempts", address));
        issue(address, "222222", OTP_TTL, COOLDOWN);
        assertEquals(OtpStore.VerificationResult.ACCEPTED, consume(address, "222222"));
    }
    @Test void orphanCooldownDoesNotSendUntilExpiryThenRecovers() throws Exception {
        String address = address(); pending(address);
        redis.opsForValue().set(key("cooldown", address), "1", COOLDOWN);
        assertThrows(OtpCooldownException.class, () -> send.send(new SendActivationOtpCommand(address)));
        assertNull(hash(address));
        verifyNoInteractions(email);
        assertEquals(UserStatus.PENDING, accountStatus(address));
        expireCooldown(address);
        send.send(new SendActivationOtpCommand(address));
        assertNotNull(hash(address));
        org.mockito.Mockito.verify(email).sendActivationEmail(eq(address), anyString());
    }
    @Test void smtpFailureReturns201OnlyAfterAccountCommitAndStoredOtp(CapturedOutput output) throws Exception {
        String address = address();
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(UserStatus.PENDING, accountStatus(address));
            assertEquals(hashes.hash(call.getArgument(1)), hash(address));
            throw new NotificationDeliveryException();
        }).when(email).sendActivationEmail(eq(address), anyString());
        mvc.perform(post("/api/v1/identity/register").contentType("application/json").content(body(address)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(UserStatus.PENDING, accountStatus(address));
        assertNotNull(hash(address));
        assertTrue(redis.getExpire(key("cooldown", address)) > 0);
        mvc.perform(post("/api/v1/identity/resend-verification-code").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", address)))).andExpect(status().isTooManyRequests());
        expireCooldown(address);
        AtomicReference<String> delivered = new AtomicReference<>();
        doAnswer(call -> { delivered.set(call.getArgument(1)); return null; }).when(email).sendActivationEmail(eq(address), anyString());
        send.send(new SendActivationOtpCommand(address));
        assertEquals(hashes.hash(delivered.get()), hash(address));
        verify.verify(new VerifyEmailCommand(address, delivered.get()));
        assertEquals(UserStatus.ACTIVE, accountStatus(address));
        assertNull(hash(address));
        assertFalse(output.getAll().contains(address));
        assertFalse(output.getAll().contains(delivered.get()));
    }
    @Test void redisFailureAfterCreationIs503AndResendRecoversWithoutAnotherRegistration() throws Exception {
        String address = address();
        doThrow(new RedisConnectionFailureException("fixture outage")).when(store).issue(eq(address), anyString(), any(), any());
        mvc.perform(post("/api/v1/identity/register").contentType("application/json").content(body(address)))
                .andExpect(status().isServiceUnavailable());
        assertEquals(UserStatus.PENDING, accountStatus(address));
        assertNull(hash(address));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key("cooldown", address))));
        verifyNoInteractions(email);
        doCallRealMethod().when(store).issue(eq(address), anyString(), any(), any());
        send.send(new SendActivationOtpCommand(address));
        assertNotNull(hash(address));
        assertEquals(UserStatus.PENDING, accountStatus(address));
    }
    @Test void resendSmtpFailureIs503AndKeepsStoredCode() throws Exception {
        String address = address(); pending(address);
        doThrow(new NotificationDeliveryException()).when(email).sendActivationEmail(eq(address), anyString());
        mvc.perform(post("/api/v1/identity/resend-verification-code").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", address)))).andExpect(status().isServiceUnavailable());
        assertNotNull(hash(address));
        assertEquals(UserStatus.PENDING, accountStatus(address));
        assertTrue(redis.getExpire(key("cooldown", address)) > 0);
    }
    @Test void realPostgresFailureBeforeCommitDoesNotReturnTokensOrRestoreConsumedOtp() throws Exception {
        String address = address(); pending(address); issue(address, "123456", OTP_TTL, COOLDOWN);
        doAnswer(call -> {
            call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { jdbc.execute("SELECT 1 / 0"); }
            });
            return null;
        }).when(users).save(argThat(user -> user.getEmail().equals(address) && user.isActive()));
        mvc.perform(post("/api/v1/identity/verify-email").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", address, "otp", "123456"))))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.accessToken").doesNotExist());
        assertEquals(UserStatus.PENDING, accountStatus(address));
        assertNull(hash(address));
        // Redis is not rolled back with PostgreSQL. The unreturned token expires naturally.
        assertEquals(1, refreshKeys(address).size());
        doCallRealMethod().when(users).save(any());
        expireCooldown(address);
        AtomicReference<String> delivered = new AtomicReference<>();
        doAnswer(call -> { delivered.set(call.getArgument(1)); return null; }).when(email).sendActivationEmail(eq(address), anyString());
        send.send(new SendActivationOtpCommand(address));
        verify.verify(new VerifyEmailCommand(address, delivered.get()));
        assertEquals(UserStatus.ACTIVE, accountStatus(address));
    }
    @Test void redisRefreshStorageFailureRollsBackActivationButCannotRestoreOtp() throws Exception {
        String address = address(); User user = pending(address); issue(address, "123456", OTP_TTL, COOLDOWN);
        doThrow(new RedisConnectionFailureException("fixture outage")).when(refreshStore).save(anyString(), eq(user.getId()), any());
        assertThrows(RedisConnectionFailureException.class, () -> verify.verify(new VerifyEmailCommand(address, "123456")));
        assertEquals(UserStatus.PENDING, accountStatus(address));
        assertNull(hash(address));
        assertTrue(refreshKeys(address).isEmpty());
        expireCooldown(address);
        send.send(new SendActivationOtpCommand(address));
        assertNotNull(hash(address));
    }
    @Test void concurrentHttpVerificationsActivateOnlyOnce() throws Exception {
        String address = address(); pending(address); issue(address, "123456", OTP_TTL, COOLDOWN);
        String payload = json.writeValueAsString(Map.of("email", address, "otp", "123456"));
        List<String> outcomes = race(6, () -> Integer.toString(mvc.perform(post("/api/v1/identity/verify-email")
                .contentType("application/json").content(payload)).andReturn().getResponse().getStatus()));
        assertEquals(1, Collections.frequency(outcomes, "200"));
        assertEquals(5, Collections.frequency(outcomes, "409"));
        assertEquals(UserStatus.ACTIVE, accountStatus(address));
        assertNull(hash(address));
        assertEquals(1, refreshKeys(address).size());
    }
    @Test void refreshReplayAndFailureAfterConsumptionRequireLogin() throws Exception {
        String address = address(); User user = pending(address); user.activate(); users.save(user);
        String old = tokens.generateRefreshToken(user);
        String oldHash = hashes.hash(old);
        refreshStore.save(oldHash, user.getId(), tokens.getRefreshTokenTtl());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture outage"))
                .when(users).findById(user.getId());
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> refresh.refresh(new RefreshSessionCommand(old)));
        assertFalse(refreshStore.exists(oldHash));
        assertThrows(InvalidRefreshTokenException.class, () -> refresh.refresh(new RefreshSessionCommand(old)));
        assertEquals(UserStatus.ACTIVE, accountStatus(address));
        assertTrue(refreshKeys(address).isEmpty());
    }
    @Test void concurrentRefreshAndIdempotentLogoutPreserveAtomicConsumption() throws Exception {
        String address = address(); User user = pending(address); user.activate(); users.save(user);
        String old = tokens.generateRefreshToken(user);
        refreshStore.save(hashes.hash(old), user.getId(), tokens.getRefreshTokenTtl());
        List<String> outcomes = race(6, () -> {
            try { return refresh.refresh(new RefreshSessionCommand(old)).refreshToken(); }
            catch (InvalidRefreshTokenException ex) { return "rejected"; }
        });
        assertEquals(5, Collections.frequency(outcomes, "rejected"));
        String newToken = outcomes.stream().filter(s -> !s.equals("rejected")).findFirst().orElseThrow();
        assertFalse(refreshStore.exists(hashes.hash(old)));
        assertEquals(1, refreshKeys(address).size());
        logout.logout(new LogoutCommand(newToken));
        logout.logout(new LogoutCommand(newToken));
        assertTrue(refreshKeys(address).isEmpty());
        assertEquals(UserStatus.ACTIVE, accountStatus(address));
    }
    @Test void partialAttemptLimitWithoutBlockCannotAcceptEvenTheCorrectCode() {
        String address = address(); pending(address); issue(address, "123456", OTP_TTL, COOLDOWN);
        redis.opsForValue().set(key("attempts", address), "3", OTP_TTL);
        assertEquals(OtpStore.VerificationResult.BLOCKED, consume(address, "123456"));
        assertNull(hash(address));
        assertTrue(redis.getExpire(key("blocked", address)) > 0);
        assertEquals(UserStatus.PENDING, accountStatus(address));
    }
    @Test void genuineRedisConnectionFailureLeavesCommittedAccountRecoverable() throws Exception {
        String address = address();
        try (var closedPort = new java.net.Socket()) {
            closedPort.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
            var config = new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1", closedPort.getLocalPort());
            config.setDatabase(1);
            var client = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofMillis(500)).shutdownTimeout(Duration.ZERO).build();
            var connection = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config, client);
            connection.afterPropertiesSet();
            try {
                var unavailable = new RedisOtpStore(new StringRedisTemplate(connection));
                doAnswer(call -> unavailable.issue(address, call.getArgument(1), OTP_TTL, COOLDOWN))
                        .when(store).issue(eq(address), anyString(), any(), any());
                mvc.perform(post("/api/v1/identity/register").contentType("application/json").content(body(address)))
                        .andExpect(status().isServiceUnavailable());
                assertEquals(UserStatus.PENDING, accountStatus(address));
                assertNull(hash(address));
                verifyNoInteractions(email);
            } finally { connection.destroy(); }
        }
        doCallRealMethod().when(store).issue(eq(address), anyString(), any(), any());
        send.send(new SendActivationOtpCommand(address));
        assertNotNull(hash(address));
    }
    @Test void logoutFailureDoesNotClaimSuccessOrRevokeOtherSessions() throws Exception {
        String address = address(); User user = pending(address); user.activate(); users.save(user);
        String token = tokens.generateRefreshToken(user);
        String tokenHash = hashes.hash(token);
        refreshStore.save(tokenHash, user.getId(), tokens.getRefreshTokenTtl());
        doThrow(new RedisConnectionFailureException("fixture outage")).when(refreshStore).revoke(tokenHash);
        mvc.perform(post("/api/v1/identity/logout").contentType("application/json")
                .content(json.writeValueAsString(Map.of("refreshToken", token)))).andExpect(status().isServiceUnavailable());
        assertTrue(refreshStore.exists(tokenHash));
        assertEquals(UserStatus.ACTIVE, accountStatus(address));
        doCallRealMethod().when(refreshStore).revoke(tokenHash);
        logout.logout(new LogoutCommand(token));
        assertFalse(refreshStore.exists(tokenHash));
    }
    @Test void resendWaitsForActivationTransactionAndDoesNotSendForAnActiveAccount() throws Exception {
        String address = address(); pending(address); issue(address, "123456", OTP_TTL, COOLDOWN);
        var reachedSave = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        doAnswer(call -> {
            call.callRealMethod(); reachedSave.countDown();
            if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test transaction timed out");
            return null;
        }).when(users).save(argThat(user -> user.getEmail().equals(address) && user.isActive()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var activation = pool.submit(() -> verify.verify(new VerifyEmailCommand(address, "123456")));
            assertTrue(reachedSave.await(5, TimeUnit.SECONDS));
            // This request must wait for the row lock, then re-read ACTIVE after commit.
            var resend = pool.submit(() -> {
                assertThrows(UserAlreadyActiveException.class, () -> send.send(new SendActivationOtpCommand(address)));
            });
            assertThrows(TimeoutException.class, () -> resend.get(150, TimeUnit.MILLISECONDS));
            allowCommit.countDown();
            activation.get(5, TimeUnit.SECONDS);
            resend.get(5, TimeUnit.SECONDS);
            assertEquals(UserStatus.ACTIVE, accountStatus(address));
            assertNull(hash(address));
            verifyNoInteractions(email);
        } finally { allowCommit.countDown(); pool.shutdownNow(); }
    }
    @Test void permanentAttemptLimitGetsBoundedExpiryAndAccountRecovers() throws Exception {
        String address = address(); pending(address);
        issue(address, "123456", OTP_TTL, Duration.ofMillis(30));
        redis.opsForValue().set(key("attempts", address), "3");
        assertEquals(-1L, redis.getExpire(key("attempts", address), TimeUnit.MILLISECONDS));
        assertEquals(OtpStore.VerificationResult.BLOCKED, store.verifyAndConsume(address, hashes.hash("123456"), 3,
                Duration.ofSeconds(1), Duration.ofMillis(1500)));
        assertEquals("3", redis.opsForValue().get(key("attempts", address)));
        long ttl = redis.getExpire(key("attempts", address), TimeUnit.MILLISECONDS);
        assertTrue(ttl > 0 && ttl <= 1000);
        assertNull(hash(address));
        assertEquals(UserStatus.PENDING, accountStatus(address));
        awaitExpired(key("attempts", address));
        awaitExpired(key("blocked", address));
        AtomicReference<String> delivered = new AtomicReference<>();
        doAnswer(call -> { delivered.set(call.getArgument(1)); return null; }).when(email).sendActivationEmail(eq(address), anyString());
        send.send(new SendActivationOtpCommand(address));
        assertEquals(hashes.hash(delivered.get()), hash(address));
        verify.verify(new VerifyEmailCommand(address, delivered.get()));
        assertEquals(UserStatus.ACTIVE, accountStatus(address));
        assertNull(hash(address));
    }
    @Test void existingAttemptTtlIsNotExtendedEvenWhileBlocked() {
        String address = address(); pending(address); issue(address, "123456", OTP_TTL, COOLDOWN);
        redis.opsForValue().set(key("attempts", address), "3", Duration.ofSeconds(5));
        long before = redis.getExpire(key("attempts", address), TimeUnit.MILLISECONDS);
        assertEquals(OtpStore.VerificationResult.BLOCKED, consume(address, "123456"));
        assertEquals(OtpStore.VerificationResult.BLOCKED, consume(address, "123456"));
        long after = redis.getExpire(key("attempts", address), TimeUnit.MILLISECONDS);
        assertTrue(after > 0 && after <= before);
        assertEquals("3", redis.opsForValue().get(key("attempts", address)));
        assertEquals(UserStatus.PENDING, accountStatus(address));
    }
    @Test void existingZeroCounterKeepsItsExpiryOnFirstFailure() {
        String address = address(); pending(address); issue(address, "123456", OTP_TTL, COOLDOWN);
        redis.opsForValue().set(key("attempts", address), "0", Duration.ofSeconds(5));
        long before = redis.getExpire(key("attempts", address), TimeUnit.MILLISECONDS);
        assertEquals(OtpStore.VerificationResult.INVALID, consume(address, "999999"));
        assertEquals("1", redis.opsForValue().get(key("attempts", address)));
        long after = redis.getExpire(key("attempts", address), TimeUnit.MILLISECONDS);
        assertTrue(after > 0 && after <= before);
        assertEquals(UserStatus.PENDING, accountStatus(address));
    }
    @Test void permanentCounterIsRepairedEvenWithoutOtpDuringExistingBlock() {
        String address = address(); pending(address);
        redis.opsForValue().set(key("attempts", address), "3");
        redis.opsForValue().set(key("blocked", address), "1", Duration.ofSeconds(5));
        long blockBefore = redis.getExpire(key("blocked", address), TimeUnit.MILLISECONDS);
        assertEquals(OtpStore.VerificationResult.BLOCKED, consume(address, "123456"));
        assertTrue(redis.getExpire(key("attempts", address), TimeUnit.MILLISECONDS) > 0);
        assertTrue(redis.getExpire(key("blocked", address), TimeUnit.MILLISECONDS) <= blockBefore);
        assertEquals("3", redis.opsForValue().get(key("attempts", address)));
        assertNull(hash(address));
    }
    @Test void activationCanCommitBeforeNotificationCompletesWithoutKeepingAnIssuanceLock(CapturedOutput output) throws Exception {
        String address = address(); pending(address);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> delivered = new AtomicReference<>();
        try {
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
                String code = call.getArgument(1);
                assertEquals(hashes.hash(code), hash(address));
                // A different connection must acquire the account lock and commit while SMTP waits.
                pool.submit(() -> verify.verify(new VerifyEmailCommand(address, code))).get(5, TimeUnit.SECONDS);
                assertEquals(UserStatus.ACTIVE, accountStatus(address));
                assertNull(hash(address));
                delivered.set(code); // Delivery is now late; it cannot reactivate the account.
                return null;
            }).when(email).sendActivationEmail(eq(address), anyString());
            send.send(new SendActivationOtpCommand(address));
            assertEquals(UserStatus.ACTIVE, accountStatus(address));
            assertThrows(UserAlreadyActiveException.class, () -> verify.verify(new VerifyEmailCommand(address, delivered.get())));
            assertFalse(output.getAll().contains(address));
            assertFalse(output.getAll().contains(delivered.get()));
        } finally { pool.shutdownNow(); }
    }
    @Test void replacementCanOvertakeNotificationAndOnlyNewestCodeRemainsUsable(CapturedOutput output) throws Exception {
        String address = address(); pending(address);
        doReturn("123456", "654321").when(generator).generate();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<String> delivered = new CopyOnWriteArrayList<>();
        try {
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                String code = call.getArgument(1);
                if (code.equals("123456")) {
                    expireCooldown(address); // Advance only this fixture's cooldown, not production timeouts.
                    pool.submit(() -> send.send(new SendActivationOtpCommand(address))).get(5, TimeUnit.SECONDS);
                }
                delivered.add(code);
                return null;
            }).when(email).sendActivationEmail(eq(address), anyString());
            send.send(new SendActivationOtpCommand(address));
            assertEquals(List.of("654321", "123456"), delivered);
            assertEquals(UserStatus.PENDING, accountStatus(address));
            assertEquals(hashes.hash("654321"), hash(address));
            assertThrows(InvalidOtpException.class, () -> verify.verify(new VerifyEmailCommand(address, "123456")));
            assertEquals("1", redis.opsForValue().get(key("attempts", address)));
            verify.verify(new VerifyEmailCommand(address, "654321"));
            assertEquals(UserStatus.ACTIVE, accountStatus(address));
            assertNull(hash(address));
            assertFalse(output.getAll().contains(address));
            for (String code : delivered) assertFalse(output.getAll().contains(code));
        } finally { pool.shutdownNow(); }
    }
    @Test void preparationCommitFailureDoesNotSendAndResendRecoversStoredOtp() throws Exception {
        String address = address(); pending(address);
        doAnswer(call -> {
            Object user = call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { jdbc.execute("SELECT 1 / 0"); }
            });
            return user;
        }).when(users).findByEmailForUpdate(address);
        mvc.perform(post("/api/v1/identity/resend-verification-code").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", address))))
                .andExpect(status().isInternalServerError());
        verifyNoInteractions(email);
        assertEquals(UserStatus.PENDING, accountStatus(address));
        assertNotNull(hash(address));
        assertTrue(redis.getExpire(key("cooldown", address)) > 0);
        doCallRealMethod().when(users).findByEmailForUpdate(address);
        expireCooldown(address);
        send.send(new SendActivationOtpCommand(address));
        org.mockito.Mockito.verify(email).sendActivationEmail(eq(address), anyString());
        assertNotNull(hash(address));
    }
    private List<String> race(int count, Callable<String> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
                return action.call();
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            return results;
        } finally { pool.shutdownNow(); }
    }
}
