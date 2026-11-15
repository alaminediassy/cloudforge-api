package io.cloudforge.identity.api.controller;

import io.cloudforge.identity.api.response.UserProfileResponse;
import io.cloudforge.identity.api.security.AuthenticatedUser;
import io.cloudforge.identity.application.usecase.GetMyProfileUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.cloudforge.identity.api.request.UpdateMyProfileRequest;
import io.cloudforge.identity.application.command.UpdateMyProfileCommand;
import io.cloudforge.identity.application.usecase.UpdateMyProfileUseCase;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final GetMyProfileUseCase getMyProfileUseCase;
    private final UpdateMyProfileUseCase updateMyProfileUseCase;

    @GetMapping("/me")
    public UserProfileResponse getMyProfile(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        GetMyProfileUseCase.ProfileResult result =
                getMyProfileUseCase.getProfile(authenticatedUser.userId());

        return new UserProfileResponse(
                result.id(),
                result.email(),
                result.firstName(),
                result.lastName()
        );
    }

    @PatchMapping("/me")
    public UserProfileResponse updateMyProfile(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestBody UpdateMyProfileRequest request
    ) {
        UpdateMyProfileCommand command = new UpdateMyProfileCommand(
                request.getFirstName(),
                request.getLastName()
        );

        UpdateMyProfileUseCase.UpdateProfileResult result =
                updateMyProfileUseCase.updateProfile(
                        authenticatedUser.userId(),
                        command
                );

        return new UserProfileResponse(
                result.id(),
                result.email(),
                result.firstName(),
                result.lastName()
        );
    }
}
