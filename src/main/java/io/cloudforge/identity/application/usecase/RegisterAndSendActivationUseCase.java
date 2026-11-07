package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.RegisterUserCommand;
import io.cloudforge.identity.application.command.SendActivationOtpCommand;
import io.cloudforge.identity.application.port.NotificationDeliveryException;
import io.cloudforge.identity.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RegisterAndSendActivationUseCase {
    private final RegisterUserUseCase registerUser;
    private final SendActivationOtpUseCase sendActivationOtp;

    // Commit the account before trying to send its activation code.
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public User register(RegisterUserCommand command) {
        User user = registerUser.register(command);
        try {
            sendActivationOtp.send(new SendActivationOtpCommand(user.getEmail()));
        } catch (NotificationDeliveryException ex) {
            // 201 confirms account creation, not delivery. Redis errors still propagate.
            log.warn("Activation notification unavailable after registration; recovery requires OTP resend");
        }
        return user;
    }
}
