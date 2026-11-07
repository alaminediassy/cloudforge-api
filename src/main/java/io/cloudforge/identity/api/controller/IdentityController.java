package io.cloudforge.identity.api.controller;

import io.cloudforge.identity.api.request.*;
import io.cloudforge.identity.api.response.LoginUserResponse;
import io.cloudforge.identity.api.response.RefreshSessionResponse;
import io.cloudforge.identity.api.response.RegisterUserResponse;
import io.cloudforge.identity.api.response.VerifyEmailResponse;
import io.cloudforge.identity.application.command.*;
import io.cloudforge.identity.application.usecase.*;
import io.cloudforge.identity.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity")
@RequiredArgsConstructor
public class IdentityController {

    private final RegisterAndSendActivationUseCase registerUserUseCase;
    private final LoginUserUseCase loginUserUseCase;
    private final SendActivationOtpUseCase sendActivationOtpUseCase;
    private final VerifyEmailUseCase verifyEmailUseCase;
    private final RefreshSessionUseCase refreshSessionUseCase;
    private final LogoutUseCase logoutUseCase;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterUserResponse register(@Valid @RequestBody RegisterUserRequest request) {
        RegisterUserCommand command = new RegisterUserCommand(
                request.email(),
                request.firstName(),
                request.lastName(),
                request.password()
        );

        User user = registerUserUseCase.register(command);

        return new RegisterUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus()
        );
    }

    @PostMapping("/verify-email")
    public ResponseEntity<VerifyEmailResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request
    ) {
        VerifyEmailUseCase.VerifyEmailResult result =
                verifyEmailUseCase.verify(
                        new VerifyEmailCommand(
                                request.email(),
                                request.otp()
                        )
                );

        return ResponseEntity.ok(
                new VerifyEmailResponse(
                        result.accessToken(),
                        result.refreshToken()
                )
        );
    }

    @PostMapping("/resend-verification-code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerificationCode(@Valid @RequestBody ResendVerificationCodeRequest request) {
        sendActivationOtpUseCase.send(
                new SendActivationOtpCommand(request.email())
        );
    }

    @PostMapping("/login")
    public ResponseEntity<LoginUserResponse> login(@Valid @RequestBody LoginUserRequest request) {
        LoginUserUseCase.LoginResult result = loginUserUseCase.login(
                new LoginUserCommand(
                        request.email(),
                        request.password()
                )
        );

        return ResponseEntity.ok(
                new LoginUserResponse(
                        result.accessToken(),
                        result.refreshToken()
                )
        );
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshSessionResponse> refresh(
            @Valid @RequestBody RefreshSessionRequest request
    ) {
        RefreshSessionUseCase.RefreshSessionResult result =
                refreshSessionUseCase.refresh(
                        new RefreshSessionCommand(request.refreshToken())
                );

        return ResponseEntity.ok(
                new RefreshSessionResponse(
                        result.accessToken(),
                        result.refreshToken()
                )
        );
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @Valid @RequestBody LogoutRequest request
    ) {
        logoutUseCase.logout(
                new LogoutCommand(request.refreshToken())
        );
    }
}
