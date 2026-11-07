package io.cloudforge.identity.api;

import io.cloudforge.identity.api.controller.IdentityController;
import io.cloudforge.identity.api.security.AuthenticatedUser;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.application.usecase.*;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.UserStatus;
import io.cloudforge.identity.infrastructure.security.JwtTokenProvider;
import io.cloudforge.shared.configuration.JwtProperties;
import io.cloudforge.shared.configuration.SecurityConfiguration;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IdentityController.class)
@Import({SecurityConfiguration.class, JwtTokenProvider.class, AuthenticateAccessTokenUseCase.class,
        SecurityHttpTest.ProbeController.class})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SecurityHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired JwtProperties properties;
    @MockitoBean UserRepository users;
    @MockitoBean RegisterAndSendActivationUseCase register;
    @MockitoBean LoginUserUseCase login;
    @MockitoBean SendActivationOtpUseCase send;
    @MockitoBean VerifyEmailUseCase verify;
    @MockitoBean RefreshSessionUseCase refresh;
    @MockitoBean LogoutUseCase logout;
    private final User active = User.restore(UUID.randomUUID(), "private@example.org", "First", "Last", "private-hash", UserStatus.ACTIVE);

    @RestController
    static class ProbeController {
        // Test-only endpoint: no production API contract is added.
        @GetMapping("/api/v1/identity/private-probe")
        Map<String, Object> principal(Authentication authentication, HttpServletRequest request) {
            AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
            return Map.of("userId", principal.userId().toString(), "name", principal.getName(),
                    "hasCredentials", authentication.getCredentials() != null,
                    "authorityCount", authentication.getAuthorities().size(),
                    "hasSession", request.getSession(false) != null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "login", "verify-email", "resend-verification-code", "refresh", "logout"})
    void publicPostRoutesReachMvcWithoutAuthentication(String route) throws Exception {
        mvc.perform(post("/api/v1/identity/" + route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(users);
    }

    @Test void unlistedRoutesAndMethodsRequireAuthentication() throws Exception {
        for (String path : List.of("/api/v1/identity/private-probe", "/api/v1/identity/new-route", "/future-module")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.message").value("Authentication is required or invalid."));
        }
        mvc.perform(get("/api/v1/identity/register")).andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @Test void realIssuedAccessTokenCreatesMinimalPrincipalAndNoSession() throws Exception {
        when(users.findById(active.getId())).thenReturn(Optional.of(active));
        String token = tokens.generateAccessToken(active);
        mvc.perform(get("/api/v1/identity/private-probe").header("Authorization", "bEaReR " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(active.getId().toString()))
                .andExpect(jsonPath("$.name").value(active.getId().toString()))
                .andExpect(jsonPath("$.hasCredentials").value(false))
                .andExpect(jsonPath("$.authorityCount").value(0))
                .andExpect(jsonPath("$.hasSession").value(false))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(not(containsString(active.getEmail()))));
        verify(users).findById(active.getId());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        mvc.perform(get("/api/v1/identity/private-probe")).andExpect(status().isUnauthorized());
    }

    @Test void sessionAuthenticationAndQueryTokensAreNotAccepted() throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new AuthenticatedUser(active.getId()), null, List.of()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        mvc.perform(get("/api/v1/identity/private-probe").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/identity/private-probe").param("access_token", tokens.generateAccessToken(active)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "tampered", "wrong-signature", "refresh", "wrong-issuer", "missing-issuer",
            "missing-expiration", "missing-subject", "bad-subject", "short-uuid", "missing-type", "lowercase-type", "wrong-algorithm", "unsigned", "malformed"})
    void rejectsInvalidJwtBeforeDatabaseAccess(String kind, CapturedOutput output) throws Exception {
        String token = invalidToken(kind);
        mvc.perform(get("/api/v1/identity/private-probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication is required or invalid."))
                .andExpect(content().string(not(containsString(token))));
        verifyNoInteractions(users);
        assertFalse(output.getAll().contains(token));
        assertFalse(output.getAll().contains(active.getEmail()));
    }

    private String invalidToken(String kind) {
        if (kind.equals("refresh")) return tokens.generateRefreshToken(active);
        if (kind.equals("malformed")) return "malformed-private-token";
        if (kind.equals("tampered")) {
            String value = tokens.generateAccessToken(active);
            int signature = value.lastIndexOf('.') + 1;
            return value.substring(0, signature) + (value.charAt(signature) == 'A' ? 'B' : 'A') + value.substring(signature + 1);
        }
        var builder = Jwts.builder();
        if (!kind.equals("missing-subject")) builder.subject(switch (kind) {
            case "bad-subject" -> "not-a-uuid";
            case "short-uuid" -> "1-1-1-1-1";
            default -> active.getId().toString();
        });
        if (!kind.equals("missing-type")) builder.claim("type", kind.equals("lowercase-type") ? "access" : "ACCESS");
        if (!kind.equals("missing-issuer")) builder.issuer(kind.equals("wrong-issuer") ? "other-issuer" : properties.issuer());
        if (!kind.equals("missing-expiration")) builder.expiration(Date.from(Instant.now().plusSeconds(kind.equals("expired") ? -60 : 300)));
        if (kind.equals("unsigned")) return builder.compact();
        byte[] bytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        var key = Keys.hmacShaKeyFor(kind.equals("wrong-signature") ? "z".repeat(bytes.length).getBytes(StandardCharsets.UTF_8) : bytes);
        return kind.equals("wrong-algorithm") ? builder.signWith(key, Jwts.SIG.HS256).compact() : builder.signWith(key).compact();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer", "Bearer ", "Basic abc", "Bearer a,b", "Bearer a b"})
    void malformedAuthorizationHeaderIsRejected(String header) throws Exception {
        mvc.perform(get("/api/v1/identity/private-probe").header("Authorization", header)).andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @Test void multipleHeadersAreRejected() throws Exception {
        String token = tokens.generateAccessToken(active);
        mvc.perform(get("/api/v1/identity/private-probe").header("Authorization", "Bearer " + token, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "PENDING", "SUSPENDED"})
    void rejectsUnavailableOrInactiveUser(String state) throws Exception {
        Optional<User> user = state.equals("missing") ? Optional.empty() : Optional.of(User.restore(
                active.getId(), active.getEmail(), "First", "Last", "hash", UserStatus.valueOf(state)));
        when(users.findById(active.getId())).thenReturn(user);
        mvc.perform(get("/api/v1/identity/private-probe").header("Authorization", "Bearer " + tokens.generateAccessToken(active)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Authentication is required or invalid."));
    }

    @Test void validAuthenticationDoesNotGrantForbiddenActuatorAccess() throws Exception {
        when(users.findById(active.getId())).thenReturn(Optional.of(active));
        mvc.perform(get("/actuator/env").header("Authorization", "Bearer " + tokens.generateAccessToken(active)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Access is denied."));
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test void explicitInvalidBearerIsRejectedEvenOnPublicRoute() throws Exception {
        mvc.perform(post("/api/v1/identity/refresh").header("Authorization", "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"unused\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(refresh, users);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseOutageFailsClosedWithoutLeakingDetails(boolean transactionFailure, CapturedOutput output) throws Exception {
        RuntimeException failure = transactionFailure
                ? new org.springframework.transaction.CannotCreateTransactionException("PRIVATE_DATABASE_SENTINEL")
                : new DataAccessResourceFailureException("PRIVATE_DATABASE_SENTINEL");
        when(users.findById(active.getId())).thenThrow(failure);
        mvc.perform(get("/api/v1/identity/private-probe").header("Authorization", "Bearer " + tokens.generateAccessToken(active)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("Service temporarily unavailable. Please try again later."));
        assertFalse(output.getAll().contains("PRIVATE_DATABASE_SENTINEL"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void unexpectedAuthenticationFailureIsSafe(CapturedOutput output) throws Exception {
        when(users.findById(active.getId())).thenThrow(new IllegalStateException("PRIVATE_FAILURE_SENTINEL"));
        mvc.perform(get("/api/v1/identity/private-probe").header("Authorization", "Bearer " + tokens.generateAccessToken(active)))
                .andExpect(status().isInternalServerError());
        assertFalse(output.getAll().contains("PRIVATE_FAILURE_SENTINEL"));
    }
}
