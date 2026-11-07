package io.cloudforge.identity.application.usecase;

import io.cloudforge.identity.application.command.SendActivationOtpCommand;
import io.cloudforge.identity.application.port.EmailProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SendActivationOtpUseCase {
    private final PrepareActivationOtpUseCase prepareActivationOtp;
    private final EmailProvider emailProvider;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void send(SendActivationOtpCommand command) {
        // A separate Spring bean commits and releases the row lock before SMTP.
        String otp = prepareActivationOtp.prepare(command);
        // The code may be used or replaced before delivery. Never restore or log it here.
        emailProvider.sendActivationEmail(command.email(), otp);
    }
}
