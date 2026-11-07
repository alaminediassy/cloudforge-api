package io.cloudforge.identity.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudforge.identity.api.controller.IdentityController;
import io.cloudforge.identity.application.usecase.*;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.exception.*;
import io.cloudforge.shared.configuration.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IdentityController.class)
@Import(SecurityConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class IdentityHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AuthenticateAccessTokenUseCase authenticate;
    @MockitoBean RegisterAndSendActivationUseCase register;
    @MockitoBean LoginUserUseCase login;
    @MockitoBean SendActivationOtpUseCase send;
    @MockitoBean VerifyEmailUseCase verify;
    @MockitoBean RefreshSessionUseCase refresh;
    @MockitoBean LogoutUseCase logout;

    private String registration(String email, String firstName, String password) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "firstName", firstName, "lastName", "Last", "password", password));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "utf8"})
    void rejectsPasswordsOver72BytesBeforeUseCases(String kind) throws Exception {
        String password = kind.equals("ascii") ? "a".repeat(73) : "é".repeat(36) + "x";
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration("user@example.org", "First", password)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("72 UTF-8 bytes")))
                .andExpect(content().string(not(containsString(password))));
        mvc.perform(post("/api/v1/identity/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "user@example.org", "password", password))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(register, login, send);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "utf8"})
    void accepts72BytesWithoutChangingInput(String kind) throws Exception {
        String password = kind.equals("ascii") ? "a".repeat(72) : "é".repeat(36);
        when(register.register(any())).thenReturn(User.create(UUID.randomUUID(), "user@example.org", "First", "Last", "hash"));
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration("user@example.org", "First", password)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        verify(register).register(argThat(c -> password.equals(c.password())));
    }

    @Test void rejectsOversizedSchemaFields() throws Exception {
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration("user@example.org", "f".repeat(101), "password")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("firstName")));
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration("a".repeat(256)+"@example.org", "First", "password")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("255")));
        verifyNoInteractions(register, send);
    }

    @Test void rejectsMissingPasswordAndShortPassword() throws Exception {
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.org\",\"firstName\":\"First\",\"lastName\":\"Last\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration("user@example.org", "First", "short")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(register, send);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "{\"email\":{\"private\":\"do-not-echo\"}}"})
    void invalidJsonHasStable400(String body) throws Exception {
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid request."))
                .andExpect(jsonPath("$.path").value("/api/v1/identity/register"))
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.error").value("Bad Request"));
        verifyNoInteractions(register);
    }

    @Test void preservesMvcStatusAndAllowHeader() throws Exception {
        mvc.perform(get("/api/v1/identity/register").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                        org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                                new io.cloudforge.identity.api.security.AuthenticatedUser(UUID.randomUUID()), null, java.util.List.of()))))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", containsString("POST")));
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.TEXT_PLAIN).content("invalid"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test void duplicateEmailReturnsSafeConflict() throws Exception {
        when(register.register(any())).thenThrow(new UserAlreadyExistsException("private@example.org"));
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration("user@example.org", "First", "password")))
                .andExpect(status().isConflict()).andExpect(content().string(not(containsString("private@example.org"))));
        verifyNoInteractions(send);
    }

    @Test void unexpectedErrorDoesNotExposePayloadInResponseOrLogs(CapturedOutput output) throws Exception {
        when(register.register(any())).thenThrow(new IllegalStateException("SENSITIVE_SQL_PASSWORD_SENTINEL",
                new RuntimeException("SENSITIVE_CAUSE_SENTINEL")));
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration("user@example.org", "First", "password")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
        assertFalse(output.getAll().contains("SENSITIVE_SQL_PASSWORD_SENTINEL"));
        assertFalse(output.getAll().contains("SENSITIVE_CAUSE_SENTINEL"));
        assertTrue(output.getAll().contains("java.lang.IllegalStateException"));
    }

    @Test void dependencyOutageReturns503() throws Exception {
        when(register.register(any())).thenThrow(new DataAccessResourceFailureException("internal-host:5432"));
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration("user@example.org", "First", "password")))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(not(containsString("internal-host"))));
    }

    @Test void suspendedAccountConflictDoesNotDiscloseStatus() throws Exception {
        when(verify.verify(any())).thenThrow(new InvalidUserStateException());
        mvc.perform(post("/api/v1/identity/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.org\",\"otp\":\"123456\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Account cannot be activated."));
    }
    @ParameterizedTest
    @CsvSource({"credentials,401", "refresh,401", "unknown,404", "active,409", "otp,400", "blocked,429", "cooldown,429"})
    void preservesExistingBusinessStatusesWithoutEchoingUserData(String scenario, int expected) throws Exception {
        String endpoint;
        String body;
        switch (scenario) {
            case "credentials" -> {
                when(login.login(any())).thenThrow(new InvalidCredentialsException());
                endpoint = "login";
                body = "{\"email\":\"user@example.org\",\"password\":\"password\"}";
            }
            case "refresh" -> {
                when(refresh.refresh(any())).thenThrow(new InvalidRefreshTokenException());
                endpoint = "refresh";
                body = "{\"refreshToken\":\"test-token\"}";
            }
            case "cooldown" -> {
                doThrow(new OtpCooldownException("private@example.org")).when(send).send(any());
                endpoint = "resend-verification-code";
                body = "{\"email\":\"user@example.org\"}";
            }
            default -> {
                CloudForgeException error = switch (scenario) {
                    case "unknown" -> new UserNotFoundException("private@example.org");
                    case "active" -> new UserAlreadyActiveException("private@example.org");
                    case "otp" -> new InvalidOtpException();
                    default -> new VerificationBlockedException("private@example.org");
                };
                when(verify.verify(any())).thenThrow(error);
                endpoint = "verify-email";
                body = "{\"email\":\"user@example.org\",\"otp\":\"123456\"}";
            }
        }
        mvc.perform(post("/api/v1/identity/" + endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andExpect(jsonPath("$.status").value(expected))
                .andExpect(content().string(not(containsString("private@example.org"))));
    }
    @ParameterizedTest
    @ValueSource(strings = {"ascii", "utf8", "emoji"})
    void loginAcceptsExactly72Utf8BytesWithoutChangingPassword(String kind) throws Exception {
        String password = switch (kind) {
            case "ascii" -> "a".repeat(72);
            case "utf8" -> "é".repeat(36);
            default -> "🔐".repeat(18);
        };
        assertEquals(72, password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        when(login.login(any())).thenReturn(new LoginUserUseCase.LoginResult("test-access", "test-refresh"));
        mvc.perform(post("/api/v1/identity/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "user@example.org", "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("test-access"));
        verify(login).login(argThat(command -> password.equals(command.password())));
    }

    @Test void jdbcCauseDiagnosticsExcludeMessagesAndUnsafeSqlStates(CapturedOutput output) throws Exception {
        java.sql.SQLException root = new java.sql.SQLException("PRIVATE_SQL_SENTINEL", "23505");
        root.setNextException(new java.sql.SQLException("PRIVATE_NEXT_SENTINEL", "private-invalid-state"));
        when(register.register(any())).thenThrow(new IllegalStateException("PRIVATE_OUTER_SENTINEL", root));
        mvc.perform(post("/api/v1/identity/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration("user@example.org", "First", "password")))
                .andExpect(status().isInternalServerError());
        assertTrue(output.getAll().contains("java.sql.SQLException[sqlState=23505]"));
        assertFalse(output.getAll().contains("PRIVATE_SQL_SENTINEL"));
        assertFalse(output.getAll().contains("PRIVATE_NEXT_SENTINEL"));
        assertFalse(output.getAll().contains("PRIVATE_OUTER_SENTINEL"));
        assertFalse(output.getAll().contains("private-invalid-state"));
    }
}
