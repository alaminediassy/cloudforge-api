package io.cloudforge.identity.api.controller;

import io.cloudforge.identity.api.request.LoginUserRequest;
import io.cloudforge.identity.api.request.RegisterUserRequest;
import io.cloudforge.identity.api.request.ResendVerificationCodeRequest;
import io.cloudforge.identity.api.request.VerifyEmailRequest;
import io.cloudforge.identity.api.response.LoginUserResponse;
import io.cloudforge.identity.api.response.RegisterUserResponse;
import io.cloudforge.identity.api.response.VerifyEmailResponse;
import io.cloudforge.identity.application.command.LoginUserCommand;
import io.cloudforge.identity.application.command.RegisterUserCommand;
import io.cloudforge.identity.application.command.SendActivationOtpCommand;
import io.cloudforge.identity.application.command.VerifyEmailCommand;
import io.cloudforge.identity.application.usecase.LoginUserUseCase;
import io.cloudforge.identity.application.usecase.RegisterUserUseCase;
import io.cloudforge.identity.application.usecase.SendActivationOtpUseCase;
import io.cloudforge.identity.application.usecase.VerifyEmailUseCase;
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

    private final RegisterUserUseCase registerUserUseCase;
    private final LoginUserUseCase loginUserUseCase;
    private final SendActivationOtpUseCase sendActivationOtpUseCase;
    private final VerifyEmailUseCase verifyEmailUseCase;

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
        sendActivationOtpUseCase.send(new SendActivationOtpCommand(user.getEmail()));

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
    public ResponseEntity<LoginUserResponse> login(
            @Valid @RequestBody LoginUserRequest request
    ) {
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
}
