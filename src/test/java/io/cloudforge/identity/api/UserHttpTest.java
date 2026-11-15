package io.cloudforge.identity.api;

import io.cloudforge.identity.api.controller.UserController;
import io.cloudforge.identity.application.port.UserRepository;
import io.cloudforge.identity.application.usecase.AuthenticateAccessTokenUseCase;
import io.cloudforge.identity.application.usecase.GetMyProfileUseCase;
import io.cloudforge.identity.application.usecase.UpdateMyProfileUseCase;
import io.cloudforge.identity.domain.User;
import io.cloudforge.identity.domain.UserStatus;
import io.cloudforge.identity.infrastructure.security.JwtTokenProvider;
import io.cloudforge.shared.configuration.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import({
        SecurityConfiguration.class,
        JwtTokenProvider.class,
        AuthenticateAccessTokenUseCase.class,
        GetMyProfileUseCase.class,
        UpdateMyProfileUseCase.class
})
@ActiveProfiles("test")
class UserHttpTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtTokenProvider tokens;

    @MockitoBean
    private UserRepository users;

    private final User activeUser = User.restore(
            UUID.randomUUID(),
            "adama.ndiaye@example.test",
            "Adama",
            "Ndiaye",
            "unused-password-hash",
            UserStatus.ACTIVE
    );

    @Test
    void shouldReturnAuthenticatedUserProfile() throws Exception {
        when(users.findById(activeUser.getId()))
                .thenReturn(Optional.of(activeUser));

        String token = tokens.generateAccessToken(activeUser);

        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.id").value(activeUser.getId().toString()))
                .andExpect(jsonPath("$.email").value("adama.ndiaye@example.test"))
                .andExpect(jsonPath("$.firstName").value("Adama"))
                .andExpect(jsonPath("$.lastName").value("Ndiaye"));

        // Authentication and profile loading each read the account.
        verify(users, times(2)).findById(activeUser.getId());
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest
    @MethodSource("profileChanges")
    void shouldUpdateAuthenticatedUserProfile(
            String body,
            String expectedFirstName,
            String expectedLastName
    ) throws Exception {
        when(users.findById(activeUser.getId()))
                .thenReturn(Optional.of(activeUser));
        when(users.findByIdForUpdate(activeUser.getId()))
                .thenReturn(Optional.of(activeUser));
        when(users.updateNames(
                activeUser.getId(), expectedFirstName, expectedLastName
        )).thenReturn(1);

        String token = tokens.generateAccessToken(activeUser);

        mvc.perform(patch("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("userId", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.id").value(activeUser.getId().toString()))
                .andExpect(jsonPath("$.email").value("adama.ndiaye@example.test"))
                .andExpect(jsonPath("$.firstName").value(expectedFirstName))
                .andExpect(jsonPath("$.lastName").value(expectedLastName));

        var order = inOrder(users);
        order.verify(users).findById(activeUser.getId());
        order.verify(users).findByIdForUpdate(activeUser.getId());
        order.verify(users).updateNames(
                activeUser.getId(), expectedFirstName, expectedLastName
        );
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectPatchWithoutValidAuthentication(
            boolean sendInvalidToken
    ) throws Exception {
        var request = patch("/api/v1/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"firstName":"Élodie"}
                        """);

        if (sendInvalidToken) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");
        }

        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.message")
                        .value("Authentication is required or invalid."));

        verifyNoInteractions(users);
    }

    private static Stream<Arguments> profileChanges() {
        return Stream.of(
                Arguments.of(
                        """
                        {"firstName":"Élodie"}
                        """,
                        "Élodie", "Ndiaye"
                ),
                Arguments.of(
                        """
                        {"lastName":"Martin"}
                        """,
                        "Adama", "Martin"
                ),
                Arguments.of(
                        """
                        {"firstName":"Élodie","lastName":"Martin"}
                        """,
                        "Élodie", "Martin"
                )
        );
    }

    @Test
    void shouldRejectMissingAuthentication() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.message")
                        .value("Authentication is required or invalid."));

        verifyNoInteractions(users);
    }

    @Test
    void shouldRejectInvalidTokenBeforeReadingAccount() throws Exception {
        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        verifyNoInteractions(users);
    }

    @Test
    void shouldRejectAccountRemovedAfterAuthentication() throws Exception {
        when(users.findById(activeUser.getId()))
                .thenReturn(Optional.of(activeUser))
                .thenReturn(Optional.empty());

        String token = tokens.generateAccessToken(activeUser);

        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message")
                        .value("Authentication is required or invalid."))
                .andExpect(jsonPath("$.path").value("/api/v1/users/me"));

        verify(users, times(2)).findById(activeUser.getId());
        verifyNoMoreInteractions(users);
    }

    @Test
    void shouldRejectAccountSuspendedAfterAuthentication() throws Exception {
        User suspendedUser = User.restore(
                activeUser.getId(),
                activeUser.getEmail(),
                activeUser.getFirstName(),
                activeUser.getLastName(),
                "unused-password-hash",
                UserStatus.SUSPENDED
        );

        when(users.findById(activeUser.getId()))
                .thenReturn(Optional.of(activeUser))
                .thenReturn(Optional.of(suspendedUser));

        String token = tokens.generateAccessToken(activeUser);

        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.message")
                        .value("Authentication is required or invalid."));

        verify(users, times(2)).findById(activeUser.getId());
        verifyNoMoreInteractions(users);
    }
}
